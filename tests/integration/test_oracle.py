"""SQL e API contra o Oracle: os casos do Excel, um conjunto aleatório com eventos nas bordas das janelas
conferido contra as duas referências, a equivalência completa pela API, idempotência, plano de execução,
backfill, importar e retenção."""

import random
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import httpx
import orjson
import pytest

from sentinela import backfill, importar
from sentinela.api.app import create_app
from sentinela.api.models import AbrIn, TrxIn, VuIn
from sentinela.db import plan, rows
from sentinela.db.pool import open_async_pool
from sentinela.db.store import AsyncStore
from sentinela.domain import memory, variables
from sentinela.domain.events import T0, Abr, Trx, Vu
from sentinela.verification import equivalence, reference, scenarios
from tests.unit.test_equivalencia import HttpxClient

FIXTURE = scenarios.excel_fixture()


def assert_vars(got: dict, want: dict, ctx: object = "") -> None:
    assert list(got) == list(variables.NAMES)
    for name, w in want.items():
        g = got[name]
        if w is None:
            assert g is None, (ctx, name, g)
        else:
            assert g == pytest.approx(float(w), rel=1e-12, abs=1e-12), (ctx, name, g, w)


def fixture_events(zone):
    return ([TrxIn.model_validate(r).to_domain(zone) for r in FIXTURE["trx"]],
            [AbrIn.model_validate(r).to_domain(zone) for r in FIXTURE["abr"]],
            [VuIn.model_validate(r).to_domain(zone) for r in FIXTURE["vu"]])


def load(m, trx, abr, vu) -> None:
    for kind, events in ((rows.TRX, trx), (rows.ABR, abr), (rows.VU, vu)):
        r = m.bulk_insert(kind, events)
        assert r.erros == [] and r.novas == len(events), (kind.table, r)


def test_casos_do_excel_no_sql(m, env):
    trx, abr, vu = fixture_events(env.zone)
    load(m, trx, abr, vu)
    by_id = {t.id_trx: t for t in trx}
    for caso in FIXTURE["casos"]:
        assert_vars(m.features(T0.of(by_id[caso["id_trx"]])), caso["esperado"], caso["caso"])


def test_plano_usa_os_tres_indices_cobertos(m, env):
    trx, abr, vu = fixture_events(env.zone)
    load(m, trx, abr, vu)
    m.gather_stats()
    m.features(T0.of(trx[-1]))
    lines = m.read_plan()
    assert plan.problems(lines) == [], "\n".join(lines)


# ------------------------------------------------------------------ conjunto aleatório nas bordas das janelas

_OFFSETS = [0, 1, 2, 299, 300, 301, 3599, 3600, 3601, 10_799, 10_800, 10_801, 86_399, 86_400, 86_401,
            7_948_799, 7_948_800, 7_948_801, -1, -60]


def _random_events(rng: random.Random, t0s: dict[str, datetime]):
    trx, abr, vu = [], [], []
    n = 0
    for cli, t0 in t0s.items():
        for _ in range(60):
            off = rng.choice(_OFFSETS) if rng.random() < 0.5 else rng.randint(-100, 8_000_000)
            ts = t0 - timedelta(seconds=off)
            n += 1
            trx.append(Trx(
                f"R{n}", cli, ts, None,
                rng.choice([None, Decimal(0), Decimal("-50.00")] + [Decimal(rng.randint(1, 500_000)) / 100] * 6),
                rng.choice([None, "00", "00", "00", "05", "51"]), rng.choice([3000, 2000, 6000, 1000, None]),
                rng.choice([9, 1, 900, None, 81]), rng.choice([152, 152, 840, None]),
                rng.choice([None, "COMERCIO", "WEBPAY.CL X", "webpay"]), rng.choice([5411, 5812, None]),
                rng.choice(["COM_A", "COM_B", None]), rng.choice(["atmpos_trxs"] * 5 + ["otro", None]),
                rng.choice([200] * 5 + [210, None])))
        for _ in range(15):
            ts = t0 - timedelta(seconds=rng.choice(_OFFSETS + [rng.randint(0, 8_000_000)]))
            abr.append(Abr(cli, ts, device_id=rng.choice(["D1", "D2", None]), random_seed=rng.choice(["S", None])))
            vu.append(Vu(cli, t0 - timedelta(seconds=rng.choice(_OFFSETS)), tipo=rng.choice(
                [999901, 999903, 999910, None])))
    return trx, abr, vu


def _reference_rows(trx, abr, vu):
    """Eventos do domínio no formato de linha da fonte, para a referência transcrita do Excel."""
    def t(x: Trx) -> dict:
        return {"N_ID_CUSTOMER": x.cliente, "D_TRANSMISSION_DATE_AND_TIME": x.ts, "F_AMOUNT_TRANSACTION": x.monto,
                "S_RESPONSE_CODE": x.response_code, "N_PCODE": x.pcode, "N_ENTRY_MODE": x.entry_mode,
                "N_ACQUIRING_INSTITUTION_COUNTRY_CODE": x.pais, "S_CARD_ACCEPTOR_NAME": x.nombre_comercio,
                "N_MERCHANT_TYPE": x.mcc, "S_CARD_ACCEPT_EXT_ID_CODE": x.comercio, "ORIGEN": x.origen,
                "N_MTI": x.mti}

    def a(x: Abr) -> dict:
        return {"N_ID_CUSTOMER": x.cliente, "D_TRANSMISSION_DATE_AND_TIME": x.ts} | {
            c: getattr(x, k) for c, k in zip(reference._ABR_ATTRS, ("lenguage", "color", "time_zone", "device_model",
                                                                    "device_name", "device_maker", "gps_compatible",
                                                                    "version_os", "device_id", "random_seed"),
                                             strict=True)}

    def v(x: Vu) -> dict:
        return {"N_ID_CUSTOMER": x.cliente, "D_TRANSMISSION_DATE_AND_TIME": x.ts, "N_TRANSACTION_TYPE": x.tipo}

    return [t(x) for x in trx], [a(x) for x in abr], [v(x) for x in vu]


def test_aleatorio_sql_igual_as_duas_referencias(m):
    rng = random.Random(42)
    t0s = {f"CLI_{i}": datetime(2026, 9, 30, 12) + timedelta(seconds=rng.randint(0, 86_400 * 5)) for i in range(25)}
    trx, abr, vu = _random_events(rng, t0s)
    load(m, trx, abr, vu)
    ref_trx, ref_abr, ref_vu = _reference_rows(trx, abr, vu)
    for cli, ts in t0s.items():
        t0 = T0(cli, ts, Decimal(rng.choice([1000, 0])), rng.choice([5411, None]), rng.choice(["COM_A", None]))
        sql = m.features(t0)
        mem = variables.compute(memory.aggregate(t0, trx, abr, vu), t0.monto)
        ref = reference.compute({"N_ID_CUSTOMER": cli, "D_TRANSMISSION_DATE_AND_TIME": ts,
                                 "F_AMOUNT_TRANSACTION": t0.monto, "N_MERCHANT_TYPE": t0.mcc,
                                 "S_CARD_ACCEPT_EXT_ID_CODE": t0.comercio}, ref_trx, ref_abr, ref_vu)
        assert_vars(sql, ref, cli)
        assert_vars(mem, ref, cli)


# ------------------------------------------------------------------ API contra o Oracle

@pytest.fixture
async def api(conn, env):
    async def factory():
        return AsyncStore(await open_async_pool(env, 2), env.zone)

    app = create_app(factory)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t") as c:
            yield c


async def test_api_casos_do_excel_por_lote_get_e_post(api):
    for path, key in (("/v1/trx/lote", "trx"), ("/v1/abr/lote", "abr"), ("/v1/vu/lote", "vu")):
        r = await api.post(path, json=FIXTURE[key])
        assert r.status_code == 200, r.text
        assert r.json() == {"recebidas": len(FIXTURE[key]), "novas": len(FIXTURE[key]), "duplicadas": 0,
                            "erros": []}
    for caso in FIXTURE["casos"]:
        t0_row = next(t for t in FIXTURE["trx"] if t["ID_TRX"] == caso["id_trx"])
        g = await api.get(f"/v1/features/{caso['id_trx']}")
        p = await api.post("/v1/features", json=t0_row)
        assert g.status_code == p.status_code == 200
        assert g.headers["server-timing"].startswith("db;dur=")
        assert g.json() == p.json()
        assert_vars(g.json()["variaveis"], caso["esperado"], caso["caso"])


async def test_api_idempotencia(api, m):
    trx = FIXTURE["trx"][:3]
    assert (await api.post("/v1/trx", json=trx[0])).status_code == 201
    assert (await api.post("/v1/trx", json=trx[0])).status_code == 200
    r = (await api.post("/v1/trx/lote", json=trx)).json()
    assert (r["novas"], r["duplicadas"], r["erros"]) == (2, 1, [])
    vu = FIXTURE["vu"][0] | {"ID_EVENTO": "ev-1"}
    assert (await api.post("/v1/vu", json=vu)).status_code == 201
    assert (await api.post("/v1/vu", json=vu)).status_code == 200
    assert (await api.post("/v1/vu", json=FIXTURE["vu"][0])).status_code == 201
    r = (await api.post("/v1/abr/lote", json=[FIXTURE["abr"][0] | {"ID_EVENTO": "a"}] * 2)).json()
    assert (r["novas"], r["duplicadas"]) == (1, 1)
    assert (m.count("trx"), m.count("vu"), m.count("abr")) == (3, 2, 1)


async def test_api_404_e_health(api):
    assert (await api.get("/v1/features/nao-existe")).status_code == 404
    assert (await api.get("/health")).json()["status"] == "ok"


def test_retencao_remove_particoes_antigas(m):
    old, recent = datetime(2026, 1, 10, 12), datetime(2026, 4, 20, 12)
    load(m, [Trx("A", "C", old), Trx("B", "C", recent)], [Abr("C", old, device_id="D")], [Vu("C", old, tipo=1)])
    dropped = m.drop_old_partitions(date(2026, 4, 25))
    assert {d.split(".")[0] for d in dropped} == {"trx", "abr", "vu"}
    assert (m.count("trx"), m.count("abr"), m.count("vu")) == (1, 0, 0)


async def test_equivalencia_completa_pela_api(api):
    rep = await equivalence.run(HttpxClient(api), scenarios.all_scenarios(42, 25))
    assert rep.failed == 0, "\n".join(x for x in rep.lines if x.startswith("FAIL"))


def test_backfill_pequeno_confere_o_plano(conn, m):
    now = datetime(2026, 9, 30, 12)
    total = backfill.run(conn, now, 300, 3, 42, "skewed", {"trx": 2.0, "abr": 1.0, "vu": 0.5})
    assert total == {"trx": m.count("trx"), "abr": m.count("abr"), "vu": m.count("vu")}
    assert total["trx"] == 1800 and total["abr"] > 800 and total["vu"] == 450
    v = m.features(T0("C1", now, Decimal(1000), None, None))
    assert v["CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED"] is not None


def test_importar_direto(conn, env, m, tmp_path: Path):
    p = tmp_path / "trx.ndjson"
    p.write_bytes(b"".join(orjson.dumps(r) + b"\n" for r in FIXTURE["trx"]) + b'{"ID_TRX": "x"}\n')
    s = importar.direto(conn, env.zone, "trx", p)
    assert (s.lidas, s.novas, s.invalidas) == (len(FIXTURE["trx"]) + 1, len(FIXTURE["trx"]), 1)
    assert s.erros[0].startswith(f"linha {len(FIXTURE['trx']) + 1}: ")
    for fonte in ("abr", "vu"):
        q = tmp_path / f"{fonte}.json"
        q.write_bytes(orjson.dumps(FIXTURE[fonte]))
        assert importar.direto(conn, env.zone, fonte, q).novas == len(FIXTURE[fonte])
    assert importar.direto(conn, env.zone, "trx", p).duplicadas == len(FIXTURE["trx"])
    by_id = {r["ID_TRX"]: r for r in FIXTURE["trx"]}
    for caso in FIXTURE["casos"]:
        t = TrxIn.model_validate(by_id[caso["id_trx"]]).to_domain(env.zone)
        assert_vars(m.features(T0.of(t)), caso["esperado"], caso["caso"])
