import asyncio
from collections import Counter
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import orjson
import pytest

from sentinela.api.models import AbrIn, TrxIn, VuIn
from sentinela.errors import UsageError
from sentinela.sim import runner, synth
from sentinela.sim.csvlog import HEADER, CsvLog, ErrorReporter, read_meta, update_meta, write_meta
from sentinela.sim.load import db_us, trx_ids
from sentinela.sim.samplers import MAX_MONTO, MIN_MONTO, ClientSampler, monto, np_clients, np_montos, np_rng, rng

SCL = ZoneInfo("America/Santiago")
START = datetime(2026, 9, 1)


def test_amostrador_skewed_concentra_nos_quentes():
    s = ClientSampler(10_000, "skewed", rng(42, "t"))
    draws = [s.next() for _ in range(20_000)]
    assert 0.45 < sum(1 for a in draws if a <= 100) / len(draws) < 0.56
    assert min(draws) >= 1 and max(draws) <= 10_000


def test_amostrador_determinístico_por_seed():
    a = [ClientSampler(1000, "uniform", rng(7, "x")).next() for _ in range(5)]
    b = [ClientSampler(1000, "uniform", rng(7, "x")).next() for _ in range(5)]
    assert a == b


def test_montos_limitados():
    r = rng(1, "v")
    vals = sorted(monto(r) for _ in range(20_000))
    assert MIN_MONTO <= vals[0] and vals[-1] <= MAX_MONTO
    assert 20_000 < vals[len(vals) // 2] < 30_000  # mediana perto de CLP 25.000
    v = np_montos(np_rng(1, "b"), 50_000)
    assert v.min() >= MIN_MONTO and v.max() <= MAX_MONTO
    assert 0.45 < (np_clients(np_rng(1, "a"), 50_000, 10_000, "skewed") <= 100).mean() < 0.56


def test_distribuicao_invalida():
    with pytest.raises(UsageError):
        ClientSampler(10, "zipf", rng(1, "a"))


def test_synth_trx_cobre_as_regras():
    trx = synth.Synth(200, "skewed", 1, "t").trx(START, 86_400, 20_000, 1, "B")
    assert len(trx) == 20_000 and trx[0].id_trx == "B1" and len({t.id_trx for t in trx}) == 20_000
    assert all(START <= t.ts < datetime(2026, 9, 2) for t in trx)
    flags = Counter()
    for t in trx:
        f = t.flags
        flags.update(k for k in ("base", "ap", "re", "ap_aus", "re_npd", "ap_mad") if getattr(f, k))
        flags["monto_nulo"] += t.monto is None
        flags["mcc_nulo"] += t.mcc is None
        flags["webpay"] += bool(t.nombre_comercio and "WEBPAY" in t.nombre_comercio)
    assert 0.72 < flags["ap"] / 20_000 < 0.82  # base 95% × global 90% × aprovada 90%
    assert all(flags[k] > 50 for k in ("re", "ap_aus", "re_npd", "ap_mad", "monto_nulo", "mcc_nulo", "webpay"))
    assert flags["base"] < 20_000


def test_synth_abr_e_vu():
    s = synth.Synth(100, "uniform", 1, "t")
    abr = s.abr(START, 3600, 5000, 1, "A")
    assert len(abr) == 5000 and len({a.id_evento for a in abr}) == 5000
    assert 0.02 < sum(not a.valido for a in abr) / 5000 < 0.08
    same_second = Counter((a.cliente, a.ts) for a in abr)
    assert any(n > 1 for n in same_second.values())
    vu = s.vu(START, 3600, 5000, 1, None)
    assert 0.07 < sum(v.cpass for v in vu) / 5000 < 0.13 and all(v.id_evento is None for v in vu)


def test_formato_da_fonte_volta_pelos_modelos_da_api():
    s = synth.Synth(50, "uniform", 3, "t")
    for fonte, events, model in (("trx", s.trx(START, 3600, 300, 1, "G"), TrxIn),
                                 ("abr", s.abr(START, 3600, 300, 1, "GA"), AbrIn),
                                 ("vu", s.vu(START, 3600, 300, 1, "GV"), VuIn)):
        for e in events:
            row = orjson.loads(orjson.dumps(synth.SOURCE[fonte](e)))
            assert model.model_validate(row).to_domain(SCL) == e, fonte


def test_gerador_escreve_ndjson_ordenado(tmp_path: Path):
    out = synth.generate(tmp_path, 100, "skewed", 42, {"trx": 500, "abr": 100, "vu": 0},
                         datetime(2026, 9, 30, 12), 2)
    assert out == {"trx": 500, "abr": 100}
    assert not synth.dataset_path(tmp_path, "vu").exists()
    lines = synth.dataset_path(tmp_path, "trx").read_bytes().splitlines()
    ts = [orjson.loads(x)["D_TRANSMISSION_DATE_AND_TIME"] for x in lines]
    assert ts == sorted(ts) and "2026-09-28T12:00:00" <= ts[0] and ts[-1] < "2026-09-30T12:00:00"
    ids = trx_ids(synth.dataset_path(tmp_path, "trx"))
    assert len(ids) == 500 and ids[0][0].startswith("G")


def test_csvlog_e_meta(tmp_path: Path):
    p = tmp_path / "leitor-x.csv"
    with CsvLog(p, "leitor") as log:
        log.record(1, "features_post", "C7", 1500, 900, True)
        log.record(2, "features_get", "C8", 2500, None, False)
    lines = p.read_text().splitlines()
    assert lines == [HEADER, "1,leitor,features_post,C7,1500,900,ok", "2,leitor,features_get,C8,2500,,error"]
    write_meta(p, {"run": "x"})
    update_meta(p, late_starts=0)
    assert read_meta(p) == {"run": "x", "late_starts": 0}


def test_server_timing():
    assert db_us({"Server-Timing": "db;dur=1.234"}) == 1234
    assert db_us({}) is None


async def test_runner_open_loop_e_operacao_por_item(tmp_path: Path):
    calls = []

    async def call(i: int):
        calls.append(i)
        await asyncio.sleep(0.001)
        return True, 10

    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(10_000)), runner.Step(lambda i: f"op{i % 2}", call, str), tps=200,
                             duration_s=0.5, warmup_s=0.1, concurrency=8, log=log, errors=ErrorReporter())
    assert r.dispatched == 100 and not r.source_exhausted
    assert sorted(calls) == list(range(100))
    assert r.late_starts == 0 and 0 < r.measured_starts <= 100
    ops = Counter(line.split(",")[2] for line in (tmp_path / "x.csv").read_text().splitlines()[1:])
    assert ops == {"op0": 50, "op1": 50}


async def test_runner_detecta_fila(tmp_path: Path):
    async def slow(i: int):
        await asyncio.sleep(0.05)
        return True, None

    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(200)), runner.Step("op", slow, str), tps=400, duration_s=0.6,
                             warmup_s=0, concurrency=1, log=log, errors=ErrorReporter())
    assert r.late_starts > 0 and r.max_start_lag_ms > 100


async def test_runner_erros_viram_log(tmp_path: Path):
    async def boom(i: int):
        raise RuntimeError("x")

    errors = ErrorReporter()
    with CsvLog(tmp_path / "x.csv", "t") as log:
        r = await runner.run(iter(range(5)), runner.Step("op", boom, str), tps=100, duration_s=1,
                             warmup_s=0, concurrency=2, log=log, errors=errors)
    assert r.source_exhausted and r.dispatched == 5 and errors.total == 5
    assert Counter(line.rsplit(",", 1)[1] for line in (tmp_path / "x.csv").read_text().splitlines()[1:]) == {"error": 5}
