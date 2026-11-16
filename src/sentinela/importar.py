"""Carga de um arquivo de uma fonte (TRX, ABR ou VU) com os nomes de coluna originais: NDJSON (.ndjson/.jsonl),
JSON (.json, uma lista) ou CSV (.csv, com cabeçalho). É o caminho dos dados reais, nas duas formas possíveis:

  api     posta em lotes nos endpoints /v1/<fonte>/lote (a API valida e grava, como em produção);
  direto  valida com os mesmos modelos da API e grava em massa no Oracle (para históricos grandes).

Colunas vazias do CSV viram nulo. Linhas inválidas são contadas e relatadas (as 10 primeiras com detalhe).
"""

from __future__ import annotations

import asyncio
import csv
import sys
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import tzinfo
from pathlib import Path

import orjson
from pydantic import ValidationError

from sentinela.api.models import MAX_LOTE, AbrIn, TrxIn, VuIn
from sentinela.errors import UsageError

MODELS = {"trx": TrxIn, "abr": AbrIn, "vu": VuIn}
SHOW_ERRORS = 10


@dataclass
class Summary:
    lidas: int = 0
    novas: int = 0
    duplicadas: int = 0
    invalidas: int = 0
    erros: list[str] = field(default_factory=list)

    def error(self, line: int, msg: str) -> None:
        self.invalidas += 1
        if len(self.erros) < SHOW_ERRORS:
            self.erros.append(f"linha {line}: {msg}")

    def to_json(self) -> dict:
        return {"lidas": self.lidas, "novas": self.novas, "duplicadas": self.duplicadas,
                "invalidas": self.invalidas, "erros": self.erros}


def read_rows(path: Path, sep: str = ",") -> Iterator[tuple[int, dict]]:
    """(número da linha, linha) para cada registro do arquivo."""
    if not path.exists():
        raise UsageError(f"arquivo não encontrado: {path}")
    ext = path.suffix.lower()
    if ext in (".ndjson", ".jsonl"):
        with path.open("rb") as f:
            for n, line in enumerate(f, 1):
                if line.strip():
                    yield n, _obj(orjson.loads(line), n)
    elif ext == ".json":
        data = orjson.loads(path.read_bytes())
        if not isinstance(data, list):
            raise UsageError(f"{path}: o JSON precisa ser uma lista de registros")
        for n, o in enumerate(data, 1):
            yield n, _obj(o, n)
    elif ext == ".csv":
        with path.open(encoding="utf-8-sig", newline="") as f:
            for n, r in enumerate(csv.DictReader(f, delimiter=sep), 2):
                yield n, {k.strip(): (None if v is None or v.strip() == "" else v.strip()) for k, v in r.items()
                          if k is not None}
    else:
        raise UsageError(f"formato não suportado: {path.suffix} (use .ndjson, .jsonl, .json ou .csv)")


def _obj(o: object, n: int) -> dict:
    if not isinstance(o, dict):
        raise UsageError(f"linha {n}: registro não é um objeto JSON")
    return o


def _batches(path: Path, fonte: str, sep: str, size: int, s: Summary) -> Iterator[list[tuple[int, dict]]]:
    batch: list[tuple[int, dict]] = []
    for n, row in read_rows(path, sep):
        s.lidas += 1
        batch.append((n, row))
        if len(batch) == size:
            yield batch
            batch = []
    if batch:
        yield batch


def direto(conn, zone: tzinfo, fonte: str, path: Path, sep: str = ",", size: int = 5_000) -> Summary:
    from sentinela.db import rows
    from sentinela.db.maintenance import Maintenance

    model = MODELS[fonte]
    kind = {"trx": rows.TRX, "abr": rows.ABR, "vu": rows.VU}[fonte]
    m = Maintenance(conn)
    s = Summary()
    for batch in _batches(path, fonte, sep, size, s):
        events, lines = [], []
        for n, row in batch:
            try:
                events.append(model.model_validate(row).to_domain(zone))
                lines.append(n)
            except (ValidationError, ValueError) as e:
                s.error(n, _short(e))
        r = m.bulk_insert(kind, events)
        s.novas += r.novas
        s.duplicadas += r.duplicadas
        for e in r.erros:
            s.error(lines[e["indice"]], e["erro"])
    return s


async def api(api_url: str, fonte: str, path: Path, sep: str = ",", size: int = 1_000,
              concurrency: int = 4) -> Summary:
    import aiohttp

    if not 1 <= size <= MAX_LOTE:
        raise UsageError(f"--lote precisa estar entre 1 e {MAX_LOTE}")
    s = Summary()
    url = f"{api_url}/v1/{fonte}/lote"
    sem = asyncio.Semaphore(concurrency)

    async def post(session: aiohttp.ClientSession, batch: list[tuple[int, dict]]) -> None:
        async with sem:
            body = orjson.dumps([row for _, row in batch])
            async with session.post(url, data=body, headers={"Content-Type": "application/json"}) as resp:
                payload = orjson.loads(await resp.read())
        if resp.status == 200:
            s.novas += payload["novas"]
            s.duplicadas += payload["duplicadas"]
            for e in payload["erros"]:
                s.error(batch[e["indice"]][0], e["erro"])
        elif resp.status == 422:
            # O lote inteiro é rejeitado se uma linha não valida: reenvia linha a linha para isolar as ruins.
            for n, row in batch:
                async with session.post(url, data=orjson.dumps([row]),
                                        headers={"Content-Type": "application/json"}) as r1:
                    p1 = orjson.loads(await r1.read())
                if r1.status == 200:
                    s.novas += p1["novas"]
                    s.duplicadas += p1["duplicadas"]
                else:
                    s.error(n, _short_http(p1))
        else:
            raise UsageError(f"POST {url}: {resp.status} {payload}")

    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=120)) as session:
        pending: set[asyncio.Task] = set()
        for batch in _batches(path, fonte, sep, size, s):
            pending.add(asyncio.create_task(post(session, batch)))
            if len(pending) >= concurrency * 2:
                done, pending = await asyncio.wait(pending, return_when=asyncio.FIRST_COMPLETED)
                for t in done:
                    t.result()
        for t in asyncio.as_completed(pending):
            await t
    return s


def _short(e: Exception) -> str:
    if isinstance(e, ValidationError):
        err = e.errors()[0]
        return f"{'.'.join(str(x) for x in err['loc'])}: {err['msg']}"
    return str(e)


def _short_http(p: object) -> str:
    try:
        err = p["detail"][0]  # type: ignore[index]
        return f"{'.'.join(str(x) for x in err['loc'][2:])}: {err['msg']}"
    except (KeyError, IndexError, TypeError):
        return str(p)[:200]


def print_summary(fonte: str, modo: str, path: Path, s: Summary) -> None:
    print(f"importar {fonte} ({modo}) {path}: {s.lidas} lidas, {s.novas} novas, {s.duplicadas} duplicadas, "
          f"{s.invalidas} inválidas", file=sys.stderr)
    for e in s.erros:
        print(f"  {e}", file=sys.stderr)
