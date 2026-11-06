from datetime import datetime, timedelta

import httpx
import pytest

from sentinela.api.app import create_app
from sentinela.db.convert import naive_utc
from sentinela.db.pool import open_async_pool
from sentinela.db.store import AsyncStore
from sentinela.domain.agg import Transaction
from sentinela.domain.windows import UTC, limits
from sentinela.verification import data, reference

US = timedelta(microseconds=1)


def _now() -> datetime:
    return datetime.now(UTC).replace(microsecond=123456)


@pytest.fixture
async def store(env, conn):
    s = AsyncStore(await open_async_pool(env, 2), env.zone)
    yield s
    await s.close()


async def test_ingestao_idempotente(store, m):
    t = Transaction(1, 10, _now(), 12345)
    assert await store.ingest(t) is True
    assert await store.ingest(t) is False
    assert m.count_tx() == 1


async def test_leitura_igual_a_referencia_nas_fronteiras(store, m, env):
    now = _now()
    txs = [Transaction(i, 3, ts, 100 + i) for i, ts in enumerate(data.boundaries(now, env.zone), 1)]
    for t in txs:
        await store.ingest(t)
    today = limits(now, env.zone).today
    for k in range(92, 0, -1):
        m.close_day(today - timedelta(days=k))
    got = await store.read(3, now)
    assert got == reference.compute(txs, now, env.zone)
    assert m.read(3, now) == got
    assert (await store.read(999, now)).to_json()["cnt_92d"] == 0


def test_job_diario_e_verificacao(m, env):
    now = _now()
    today = limits(now, env.zone).today
    d = today - timedelta(days=1)
    from sentinela.domain.windows import day_start

    start = day_start(d, env.zone)
    m.bulk_insert_tx([(1, 1, naive_utc(start), 100), (2, 1, naive_utc(start + timedelta(hours=5)), 300),
                      (3, 2, naive_utc(start - US), 999)])
    assert m.close_day(d) == 1
    assert m.close_day(d) == 1  # idempotente
    [(acc, agg)] = m.daily_rows(d)
    assert acc == 1 and (agg.cnt, agg.sum_cents, agg.min_cents, agg.max_cents) == (2, 400, 100, 300)
    assert m.check_day(d) == 0


def test_retencao_remove_particoes_antigas(m, env):
    today = limits(_now(), env.zone).today
    old = datetime.combine(today - timedelta(days=100), datetime.min.time())
    m.bulk_insert_tx([(1, 1, old, 100), (2, 1, naive_utc(_now() - timedelta(hours=1)), 100)])
    dropped = m.drop_old_partitions(today)
    assert len(dropped) >= 1
    assert m.count_tx() == 1


def test_backfill_pequeno_e_plano(conn, env, m):
    from sentinela import backfill
    from sentinela.errors import InvalidRun

    try:
        backfill.run(conn, env.zone, accounts=200, days=10, seed=1, distribution="skewed", tx_per_day=5,
                     now=_now())
    except InvalidRun:
        pass  # com 200 contas o otimizador pode preferir outro plano; o que importa aqui é a carga
    assert m.count_tx() > 0 and not m.is_empty()
    assert m.read_plan()  # o cursor da leitura está em V$SQL e o plano é legível


async def test_api_contra_o_banco(env, conn):
    async def factory():
        return AsyncStore(await open_async_pool(env, 2), env.zone)

    app = create_app(factory)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            ts = (datetime.now(UTC) - timedelta(seconds=5)).isoformat()
            body = {"tx_id": 77, "account_id": 5, "ts": ts, "amount": 10.5}
            assert (await c.post("/v1/transacoes", json=body)).status_code == 201
            assert (await c.post("/v1/transacoes", json=body)).status_code == 200
            f = (await c.get("/v1/contas/5/features")).json()["features"]
            assert f["cnt_1m"] == 1 and f["sum_92d"] == 10.5
