"""API com store em memória: contrato HTTP, ingestão das três fontes e os casos do Excel ponta a ponta."""

import json
from pathlib import Path
from zoneinfo import ZoneInfo

import httpx
import pytest

from sentinela.verification import scenarios
from sentinela.api.app import create_app
from sentinela.db.rows import LoteResult
from sentinela.domain import memory, variables
from sentinela.domain.events import T0

FIXTURE = scenarios.excel_fixture()


class MemoryStore:
    zone = ZoneInfo("America/Santiago")

    def __init__(self):
        self.trx: dict = {}
        self.abr: list = []
        self.vu: list = []
        self.ids: set = set()
        self.closed = False

    def _event(self, lst, e) -> bool:
        if e.id_evento is not None:
            if e.id_evento in self.ids:
                return False
            self.ids.add(e.id_evento)
        lst.append(e)
        return True

    async def ingest_trx(self, t):
        if t.id_trx in self.trx:
            return False
        self.trx[t.id_trx] = t
        return True

    async def ingest_abr(self, a):
        return self._event(self.abr, a)

    async def ingest_vu(self, v):
        return self._event(self.vu, v)

    async def _lote(self, call, events):
        new = [await call(e) for e in events]
        return LoteResult(len(events), sum(new), len(events) - sum(new), [])

    async def ingest_trx_lote(self, ts):
        return await self._lote(self.ingest_trx, ts)

    async def ingest_abr_lote(self, a):
        return await self._lote(self.ingest_abr, a)

    async def ingest_vu_lote(self, v):
        return await self._lote(self.ingest_vu, v)

    async def features(self, t0):
        return variables.compute(memory.aggregate(t0, self.trx.values(), self.abr, self.vu), t0.monto)

    async def t0_by_id(self, id_trx):
        t = self.trx.get(id_trx)
        return None if t is None else T0.of(t)

    async def ping(self):
        pass

    async def close(self):
        self.closed = True


@pytest.fixture
async def client():
    store = MemoryStore()

    async def factory():
        return store

    app = create_app(factory)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            yield c, store
    assert store.closed


TRX = {"ID_TRX": "1", "N_ID_CUSTOMER": "C1", "D_TRANSMISSION_DATE_AND_TIME": "2026-09-30T11:00:00",
       "F_AMOUNT_TRANSACTION": 1000, "S_RESPONSE_CODE": "00", "N_PCODE": 3000, "N_ENTRY_MODE": 9,
       "N_ACQUIRING_INSTITUTION_COUNTRY_CODE": 152, "N_MERCHANT_TYPE": 5411, "S_CARD_ACCEPT_EXT_ID_CODE": "COM_A",
       "ORIGEN": "atmpos_trxs", "N_MTI": 200}


async def test_trx_201_e_duplicada_200(client):
    c, store = client
    r = await c.post("/v1/trx", json=TRX)
    assert r.status_code == 201 and r.json() == {"ID_TRX": "1", "duplicada": False}
    assert r.headers["server-timing"].startswith("db;dur=")
    r = await c.post("/v1/trx", json=TRX)
    assert r.status_code == 200 and r.json()["duplicada"] is True


async def test_instante_com_fuso_vira_hora_local(client):
    c, store = client
    await c.post("/v1/trx", json=TRX | {"ID_TRX": "2", "D_TRANSMISSION_DATE_AND_TIME": "2026-09-30T14:00:00Z"})
    assert store.trx["2"].ts.isoformat() == "2026-09-30T11:00:00"


async def test_vu_sem_id_evento_aceita_repetido_e_com_id_e_idempotente(client):
    c, store = client
    vu = {"N_ID_CUSTOMER": "C1", "D_TRANSMISSION_DATE_AND_TIME": "2026-09-30T10:00:00", "N_TRANSACTION_TYPE": 999901}
    assert (await c.post("/v1/vu", json=vu)).status_code == 201
    assert (await c.post("/v1/vu", json=vu)).status_code == 201
    assert len(store.vu) == 2
    assert (await c.post("/v1/vu", json=vu | {"ID_EVENTO": "e1"})).status_code == 201
    assert (await c.post("/v1/vu", json=vu | {"ID_EVENTO": "e1"})).status_code == 200


async def test_validacao_422(client):
    c, _ = client
    assert (await c.post("/v1/trx", json={"ID_TRX": "1"})).status_code == 422
    assert (await c.post("/v1/trx", json=TRX | {"F_AMOUNT_TRANSACTION": "1.234"})).status_code == 422
    assert (await c.post("/v1/features", json={"N_ID_CUSTOMER": "C1"})).status_code == 422


async def test_get_de_id_inexistente_404(client):
    c, _ = client
    assert (await c.get("/v1/features/nao-existe")).status_code == 404


async def test_casos_do_excel_pelos_endpoints_de_lote(client):
    c, _ = client
    for path, key in (("/v1/trx/lote", "trx"), ("/v1/abr/lote", "abr"), ("/v1/vu/lote", "vu")):
        r = await c.post(path, json=FIXTURE[key])
        assert r.status_code == 200 and r.json()["novas"] == len(FIXTURE[key]), path
    for caso in FIXTURE["casos"]:
        t0_row = next(t for t in FIXTURE["trx"] if t["ID_TRX"] == caso["id_trx"])
        by_get = (await c.get(f"/v1/features/{caso['id_trx']}")).json()
        by_post = (await c.post("/v1/features", json=t0_row)).json()
        assert by_get == by_post
        assert by_get["versao"] == variables.VERSAO and by_get["N_ID_CUSTOMER"] == caso["cliente"]
        for name, want in caso["esperado"].items():
            got = by_get["variaveis"][name]
            assert (got is None) if want is None else got == pytest.approx(want, rel=1e-12), (caso["caso"], name)
