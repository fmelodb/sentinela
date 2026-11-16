"""Equivalência ponta a ponta com o store em memória (o caminho do domínio), e o leitor de arquivos do importar."""

from pathlib import Path

import httpx
import pytest

from sentinela import importar
from sentinela.api.app import create_app
from sentinela.errors import UsageError
from sentinela.verification import equivalence, scenarios

from .test_api import MemoryStore


class HttpxClient:
    def __init__(self, c: httpx.AsyncClient):
        self.c = c

    async def post(self, path, body):
        r = await self.c.post(path, content=body, headers={"Content-Type": "application/json"})
        return r.status_code, r.json()

    async def get(self, path):
        r = await self.c.get(path)
        return r.status_code, r.json()


@pytest.fixture
async def asgi():
    store = MemoryStore()

    async def factory():
        return store

    app = create_app(factory)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            yield HttpxClient(c)


async def test_todos_os_cenarios_passam_no_dominio(asgi):
    rep = await equivalence.run(asgi, scenarios.all_scenarios(42, 10))
    assert rep.failed == 0, "\n".join(x for x in rep.lines if x.startswith("FAIL"))
    assert rep.passed > 40


async def test_equivalencia_acusa_divergencia(asgi):
    s = scenarios.excel()
    bad = s.checks[0]
    s.checks[0] = scenarios.Check(bad.cenario, bad.t0, bad.id_trx,
                                  bad.esperado | {"CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED": 99})
    rep = await equivalence.run(asgi, [s])
    assert rep.failed == 1 and "Excel: CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED" in next(
        x for x in rep.lines if x.startswith("FAIL"))


def test_cenarios_tem_clientes_proprios():
    seen: dict[str, str] = {}
    for s in scenarios.all_scenarios():
        for r in s.trx + s.abr + s.vu:
            assert seen.setdefault(r["N_ID_CUSTOMER"], s.nome) == s.nome


def test_diffs():
    n = "CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED"
    assert equivalence.diffs({n: 0.1 + 0.2}, {n: 0.3}) == []
    assert equivalence.diffs({n: None}, {n: 0}) != []
    assert equivalence.diffs({equivalence.COUNT: 2}, {equivalence.COUNT: 2.0}) == []


def test_importar_le_ndjson_json_e_csv(tmp_path: Path):
    (tmp_path / "a.ndjson").write_text('{"ID_TRX":"1","N_ID_CUSTOMER":"C"}\n\n{"ID_TRX":"2"}\n')
    assert [n for n, _ in importar.read_rows(tmp_path / "a.ndjson")] == [1, 3]
    (tmp_path / "a.json").write_text('[{"x":1},{"x":2}]')
    assert [r for _, r in importar.read_rows(tmp_path / "a.json")] == [{"x": 1}, {"x": 2}]
    (tmp_path / "a.csv").write_text("﻿ID_TRX;N_PCODE;S_RESPONSE_CODE\n1;3000;\n2; ;00\n", encoding="utf-8")
    rows = list(importar.read_rows(tmp_path / "a.csv", ";"))
    assert rows == [(2, {"ID_TRX": "1", "N_PCODE": "3000", "S_RESPONSE_CODE": None}),
                    (3, {"ID_TRX": "2", "N_PCODE": None, "S_RESPONSE_CODE": "00"})]
    with pytest.raises(UsageError):
        list(importar.read_rows(tmp_path / "a.xlsx"))
