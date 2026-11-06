import asyncio
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from sentinela.backfill import BackfillGenerator
from sentinela.domain.windows import UTC, day_start
from sentinela.errors import UsageError
from sentinela.sim import dataset, runner
from sentinela.sim.csvlog import HEADER, CsvLog, ErrorReporter, read_meta, update_meta, write_meta
from sentinela.sim.load import db_us
from sentinela.sim.samplers import (MAX_CENTS, MIN_CENTS, AccountSampler, AmountSampler, np_accounts, np_amounts,
                                    np_rng, rng)

SP = ZoneInfo("America/Sao_Paulo")


def test_amostrador_skewed_concentra_nas_quentes():
    s = AccountSampler(10_000, "skewed", rng(42, "t"))
    draws = [s.next() for _ in range(20_000)]
    hot = sum(1 for a in draws if a <= 100)
    assert 0.45 < hot / len(draws) < 0.56
    assert min(draws) >= 1 and max(draws) <= 10_000


def test_amostrador_determinístico_por_seed():
    a = [AccountSampler(1000, "uniform", rng(7, "x")).next() for _ in range(5)]
    b = [AccountSampler(1000, "uniform", rng(7, "x")).next() for _ in range(5)]
    assert a == b


def test_valores_limitados():
    s = AmountSampler(rng(1, "v"))
    vals = [s.next_cents() for _ in range(20_000)]
    assert MIN_CENTS <= min(vals) and max(vals) <= MAX_CENTS
    vals.sort()
    assert 12_000 < vals[len(vals) // 2] < 18_000  # mediana perto de R$ 150


def test_versoes_numpy():
    accs = np_accounts(np_rng(1, "a"), 50_000, 10_000, "skewed")
    assert 0.45 < (accs <= 100).mean() < 0.56
    v = np_amounts(np_rng(1, "b"), 50_000)
    assert v.min() >= MIN_CENTS and v.max() <= MAX_CENTS


def test_distribuicao_invalida():
    with pytest.raises(UsageError):
        AccountSampler(10, "zipf", rng(1, "a"))


def test_dataset_ida_e_volta(tmp_path: Path):
    p = tmp_path / "tx.ndjson"
    dataset.generate(p, 100, 1234, 42, "skewed")
    rows = dataset.read_all(p)
    assert len(rows) == 1234 and rows[0].tx_id == 1 and rows[-1].tx_id == 1234
    assert all(1 <= r.account_id <= 100 and r.amount_cents >= MIN_CENTS for r in rows)
    assert p.read_text().splitlines()[0].startswith('{"tx_id":1,"account_id":')


def test_dataset_linha_ruim(tmp_path: Path):
    p = tmp_path / "tx.ndjson"
    p.write_text('{"tx_id":1,"account_id":2,"amount":1.234}\n')
    with pytest.raises(UsageError):
        dataset.read_all(p)


def test_backfill_recentes_dentro_de_d3_ate_now():
    now = datetime(2026, 10, 29, 15, 0, tzinfo=UTC)
    g = BackfillGenerator(1000, "skewed", 5, 42, SP, now)
    txs = list(g.recent_transactions())
    lo = day_start(date(2026, 10, 26), SP).replace(tzinfo=None)
    assert all(lo <= t[2] < now.replace(tzinfo=None) for t in txs)
    # 3 dias inteiros + 12 h de hoje (00:00 local = 03:00 UTC) ≈ 3,5 × 5.000
    assert 17_000 < len(txs) < 18_000
    assert len({t[0] for t in txs}) == len(txs)


def test_backfill_dia_sintetico_consistente():
    g = BackfillGenerator(1000, "skewed", 5, 42, SP, datetime(2026, 10, 29, 15, 0, tzinfo=UTC))
    rows = list(g.synthetic_day(date(2026, 9, 1)))
    assert sum(r[1] for r in rows) == 5000
    assert all(r[3] <= r[4] and r[3] * r[1] <= r[2] <= r[4] * r[1] for r in rows)
    assert rows == list(g.synthetic_day(date(2026, 9, 1)))


def test_csvlog_e_meta(tmp_path: Path):
    p = tmp_path / "leitor-x.csv"
    with CsvLog(p, "leitor") as log:
        log.record(1, "read", 7, 1500, 900, True)
        log.record(2, "read", 8, 2500, None, False)
    lines = p.read_text().splitlines()
    assert lines == [HEADER, "1,leitor,read,7,1500,900,ok", "2,leitor,read,8,2500,,error"]
    write_meta(p, {"run": "x"})
    update_meta(p, late_starts=0)
    assert read_meta(p) == {"run": "x", "late_starts": 0}


def test_server_timing():
    assert db_us({"Server-Timing": "db;dur=1.234"}) == 1234
    assert db_us({}) is None


async def test_runner_open_loop(tmp_path: Path):
    calls = []

    async def call(i: int):
        calls.append(i)
        await asyncio.sleep(0.001)
        return True, 10

    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(10_000)), runner.Step("op", call, lambda i: i), tps=200, duration_s=0.5,
                             warmup_s=0.1, concurrency=8, log=log, errors=ErrorReporter())
    assert r.dispatched == 100 and not r.source_exhausted
    assert sorted(calls) == list(range(100))
    assert r.late_starts == 0 and 0 < r.measured_starts <= 100


async def test_runner_detecta_fila_e_fonte_esgotada(tmp_path: Path):
    async def slow(i: int):
        await asyncio.sleep(0.05)
        return True, None

    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(200)), runner.Step("op", slow, lambda i: i), tps=400, duration_s=0.6,
                             warmup_s=0, concurrency=1, log=log, errors=ErrorReporter())
    assert r.late_starts > 0 and r.max_start_lag_ms > 100
    assert r.source_exhausted is False or r.dispatched <= 200


async def test_runner_erros_viram_log(tmp_path: Path):
    async def boom(i: int):
        raise RuntimeError("x")

    errors = ErrorReporter()
    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(5)), runner.Step("op", boom, lambda i: i), tps=100, duration_s=1,
                             warmup_s=0, concurrency=2, log=log, errors=errors)
    assert r.source_exhausted and r.dispatched == 5 and errors.total == 5
    assert Counter(line.rsplit(",", 1)[1] for line in (tmp_path / "x.csv").read_text().splitlines()[1:]) == {"error": 5}


def test_backfill_hoje_vazio_logo_apos_meia_noite():
    now = day_start(date(2026, 10, 29), SP) + timedelta(microseconds=1)
    txs = list(BackfillGenerator(10, "uniform", 1, 1, SP, now).recent_transactions())
    assert all(t[2] < now.replace(tzinfo=None) for t in txs)
