"""API com um store em memória (o modelo de referência): valida contrato HTTP sem banco."""

from datetime import datetime

import httpx
import pytest

from sentinela.api.app import create_app
from sentinela.domain.agg import NAMES, FeatureVector, Transaction
from sentinela.domain.windows import UTC
from sentinela.verification import reference


class MemoryStore:
    def __init__(self):
        self.txs: dict[int, Transaction] = {}
        self.closed = False

    async def ingest(self, tx: Transaction) -> bool:
        if tx.tx_id in self.txs:
            return False
        self.txs[tx.tx_id] = tx
        return True

    async def read(self, account_id: int, now: datetime) -> FeatureVector:
        from zoneinfo import ZoneInfo

        return reference.compute([t for t in self.txs.values() if t.account_id == account_id], now,
                                 ZoneInfo("America/Sao_Paulo"))

    async def ping(self) -> None:
        pass

    async def close(self) -> None:
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


async def test_ingestao_201_e_duplicada_200(client):
    c, store = client
    body = {"tx_id": 1, "account_id": 7, "ts": "2026-10-29T15:00:00.000001Z", "amount": 123.45}
    r = await c.post("/v1/transacoes", json=body)
    assert r.status_code == 201 and r.json() == {"tx_id": 1, "duplicada": False}
    assert r.headers["server-timing"].startswith("db;dur=")
    assert store.txs[1].amount_cents == 12345 and store.txs[1].ts.microsecond == 1
    r = await c.post("/v1/transacoes", json=body)
    assert r.status_code == 200 and r.json()["duplicada"] is True


@pytest.mark.parametrize("bad", [
    {"account_id": 0}, {"amount": 0}, {"amount": 1.234}, {"ts": "2026-10-29T15:00:00"}, {"tx_id": None},
])
async def test_ingestao_invalida_422(client, bad):
    c, _ = client
    body = {"tx_id": 1, "account_id": 7, "ts": "2026-10-29T15:00:00Z", "amount": 10} | bad
    assert (await c.post("/v1/transacoes", json=body)).status_code == 422


async def test_features_com_now(client):
    c, _ = client
    for i, ts in enumerate(["2026-10-29T14:59:30Z", "2026-10-29T12:00:00Z"], 1):
        await c.post("/v1/transacoes", json={"tx_id": i, "account_id": 9, "ts": ts, "amount": 10})
    r = await c.get("/v1/contas/9/features", params={"now": "2026-10-29T15:00:00Z"})
    assert r.status_code == 200 and "server-timing" in r.headers
    j = r.json()
    assert j["now"] == datetime(2026, 10, 29, 15, tzinfo=UTC).isoformat()
    f = j["features"]
    assert list(f) == list(NAMES)
    assert f["cnt_1m"] == 1 and f["cnt_5h"] == 2 and f["sum_5h"] == 20.0 and f["avg_5h"] == 10.0
    assert f["min_1m"] == 10.0


async def test_features_conta_invalida(client):
    c, _ = client
    assert (await c.get("/v1/contas/0/features")).status_code == 422


async def test_health(client):
    c, _ = client
    assert (await c.get("/health")).json() == {"status": "ok"}
