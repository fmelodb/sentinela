"""Conferência de equivalência ponta a ponta, pela API (o mesmo caminho dos dados reais):

1. ingere os cenários (scenarios.py) pelos endpoints de lote;
2. para cada T0: POST /v1/features (e GET /v1/features/{ID_TRX}, se a T0 foi ingerida, que precisa dar o mesmo),
   comparado com a referência transcrita do Excel e, nos 6 casos, com os valores da aba Output;
3. reenvia os lotes (TRX e os ABR/VU com ID_EVENTO: tudo duplicado) e ingere eventos posteriores a cada T0;
4. relê todas as T0: os valores não podem mudar.

Contagens (V4) comparam exatas; as demais, com tolerância relativa de 1e-9.
"""

from __future__ import annotations

import math
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any, Protocol

import orjson

from sentinela.api.models import MAX_LOTE
from sentinela.verification import reference
from sentinela.verification.scenarios import Check, Scenario, future_events

REL_TOL = 1e-9
COUNT = "CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED"


class Client(Protocol):
    async def post(self, path: str, body: bytes) -> tuple[int, Any]: ...

    async def get(self, path: str) -> tuple[int, Any]: ...


@dataclass
class Report:
    passed: int = 0
    failed: int = 0
    lines: list[str] = field(default_factory=list)

    def ok(self, what: str) -> None:
        self.passed += 1
        self.lines.append(f"PASS {what}")

    def fail(self, what: str, why: str) -> None:
        self.failed += 1
        self.lines.append(f"FAIL {what}: {why}")


def _default(v: object) -> str:
    if isinstance(v, Decimal):
        return str(v)
    raise TypeError(type(v).__name__)


def dumps(o: object) -> bytes:
    return orjson.dumps(o, default=_default)


def diffs(got: dict, want: dict) -> list[str]:
    out = []
    for name, w in want.items():
        g = got.get(name)
        if w is None or g is None:
            if (w is None) != (g is None):
                out.append(f"{name}: API {g} × esperado {w}")
        elif name == COUNT:
            if g != w:
                out.append(f"{name}: API {g} × esperado {w}")
        elif not math.isclose(float(g), float(w), rel_tol=REL_TOL, abs_tol=1e-12):
            out.append(f"{name}: API {g} × esperado {w}")
    return out


async def _load(client: Client, path: str, rows: Sequence[dict]) -> dict:
    total = {"recebidas": 0, "novas": 0, "duplicadas": 0, "erros": []}
    for i in range(0, len(rows), MAX_LOTE):
        status, body = await client.post(path, dumps(rows[i:i + MAX_LOTE]))
        if status != 200:
            raise RuntimeError(f"POST {path}: {status} {str(body)[:300]}")
        for k in ("recebidas", "novas", "duplicadas"):
            total[k] += body[k]
        total["erros"] += body["erros"]
    return total


async def _read(client: Client, c: Check) -> tuple[dict | None, str | None]:
    status, post = await client.post("/v1/features", dumps(c.t0))
    if status != 200:
        return None, f"POST /v1/features {status} {str(post)[:200]}"
    if c.id_trx is not None:
        status, got = await client.get(f"/v1/features/{c.id_trx}")
        if status != 200:
            return None, f"GET /v1/features/{c.id_trx} {status}"
        if got != post:
            return None, f"GET {got['variaveis']} difere do POST {post['variaveis']}"
    return post["variaveis"], None


async def run(client: Client, scenarios: Sequence[Scenario], progress: Callable[[str], None] = lambda s: None
              ) -> Report:
    rep = Report()
    trx = [r for s in scenarios for r in s.trx]
    abr = [r for s in scenarios for r in s.abr]
    vu = [r for s in scenarios for r in s.vu]
    for path, rows in (("/v1/trx/lote", trx), ("/v1/abr/lote", abr), ("/v1/vu/lote", vu)):
        r = await _load(client, path, rows)
        if r["novas"] != len(rows) or r["erros"]:
            rep.fail(f"carga {path}", f"{r['novas']} novas de {len(rows)}; erros {r['erros'][:3]}")
        else:
            rep.ok(f"carga {path}: {len(rows)} linhas")
    progress(f"carga: {len(trx)} TRX, {len(abr)} ABR, {len(vu)} VU")

    first: dict[str, dict] = {}
    for s in scenarios:
        for c in s.checks:
            got, err = await _read(client, c)
            if err is not None:
                rep.fail(c.cenario, err)
                continue
            first[c.cenario] = got
            d = diffs(got, reference.compute(c.t0, s.trx, s.abr, s.vu))
            if c.esperado is not None:
                d += [f"Excel: {x}" for x in diffs(got, c.esperado)]
            if d:
                rep.fail(c.cenario, "; ".join(d))
            else:
                rep.ok(c.cenario)
    progress(f"leituras: {len(first)} T0 conferidas")

    # Reenvio (idempotência) e dados posteriores às T0.
    with_id = [r for r in abr if r.get("ID_EVENTO")], [r for r in vu if r.get("ID_EVENTO")]
    for path, rows in (("/v1/trx/lote", trx), ("/v1/abr/lote", with_id[0]), ("/v1/vu/lote", with_id[1])):
        r = await _load(client, path, rows)
        if r["novas"] or r["duplicadas"] != len(rows):
            rep.fail(f"reenvio {path}", f"{r['novas']} novas, {r['duplicadas']} duplicadas de {len(rows)}")
        else:
            rep.ok(f"reenvio {path}: {len(rows)} duplicadas")
    fut = [future_events(s) for s in scenarios]
    for path, k in (("/v1/trx/lote", 0), ("/v1/abr/lote", 1), ("/v1/vu/lote", 2)):
        await _load(client, path, [r for f in fut for r in f[k]])
    changed = 0
    for s in scenarios:
        for c in s.checks:
            if c.cenario not in first:
                continue
            got, err = await _read(client, c)
            if err is not None or got != first[c.cenario]:
                changed += 1
                rep.fail(f"releitura {c.cenario}", err or f"{first[c.cenario]} -> {got}")
    if not changed:
        rep.ok(f"releitura após reenvio e dados futuros: {len(first)} T0 sem mudança")
    return rep


class AiohttpClient:
    def __init__(self, session, base_url: str):
        self.session = session
        self.base = base_url.rstrip("/")

    async def post(self, path: str, body: bytes) -> tuple[int, Any]:
        async with self.session.post(self.base + path, data=body,
                                     headers={"Content-Type": "application/json"}) as r:
            return r.status, orjson.loads(await r.read())

    async def get(self, path: str) -> tuple[int, Any]:
        async with self.session.get(self.base + path) as r:
            return r.status, orjson.loads(await r.read())
