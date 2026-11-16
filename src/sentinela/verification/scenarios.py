"""Cenários da conferência de equivalência: linhas das três fontes com os nomes de coluna originais e as compras
(T0) a avaliar. Cada cenário usa clientes próprios, para que um não interfira no outro.

- excel: os 6 casos do Excel de validação, com os valores esperados da aba Output;
- os testes extras pedidos na spec: bordas de janela ±1 s, faixas horárias (V8 e V9, incluindo 22h e 23h), VU
  vazio / sem CPASS / CPASS no segundo do T0 / só no futuro, ausência × monto zero × monto nulo, MCC e comercio
  nulos no T0, ABR repetido no mesmo segundo e sem atributos, vários T0 do mesmo cliente;
- aleatorio: clientes com eventos sorteados, metade deles exatamente nas bordas das janelas.

Instantes são datetimes sem fuso (hora local), montos são Decimal; a API recebe as mesmas linhas em JSON.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal
from functools import cache
from importlib import resources

T0_TS = datetime(2026, 9, 30, 12, 0, 0)
_WINDOWS = (300, 3600, 10_800, 86_400, 7_948_800)


@dataclass(frozen=True)
class Check:
    cenario: str
    t0: dict
    id_trx: str | None = None      # se a T0 foi ingerida, também se confere o GET por ID_TRX
    esperado: dict | None = None   # valores do Excel, quando houver


@dataclass
class Scenario:
    nome: str
    trx: list[dict] = field(default_factory=list)
    abr: list[dict] = field(default_factory=list)
    vu: list[dict] = field(default_factory=list)
    checks: list[Check] = field(default_factory=list)


@cache
def excel_fixture() -> dict:
    """Entradas e saídas dos 6 casos, extraídas do Excel por scripts/extrair_excel.py."""
    return json.loads(resources.files("sentinela.verification").joinpath("excel_casos.json")
                      .read_text(encoding="utf-8"))


# ------------------------------------------------------------------ construtores de linhas

class _Ids:
    def __init__(self, prefix: str):
        self.prefix = prefix
        self.n = 0

    def __call__(self) -> str:
        self.n += 1
        return f"{self.prefix}{self.n}"


def trx(id_trx: str, cli: str, ts: datetime, monto: object = Decimal(1000), resp: str | None = "00",
        pcode: int | None = 3000, entry: int | None = 9, pais: int | None = 152, nombre: str | None = "COMERCIO",
        mcc: int | None = 5411, com: str | None = "COM_A", origen: str | None = "atmpos_trxs",
        mti: int | None = 200) -> dict:
    return {"ID_TRX": id_trx, "N_ID_CUSTOMER": cli, "D_TRANSMISSION_DATE_AND_TIME": ts,
            "FECHA_INFORMACION": ts.replace(hour=0, minute=0, second=0), "F_AMOUNT_TRANSACTION": monto,
            "S_RESPONSE_CODE": resp, "N_PCODE": pcode, "N_ENTRY_MODE": entry,
            "N_ACQUIRING_INSTITUTION_COUNTRY_CODE": pais, "S_CARD_ACCEPTOR_NAME": nombre, "N_MERCHANT_TYPE": mcc,
            "S_CARD_ACCEPT_EXT_ID_CODE": com, "ORIGEN": origen, "N_MTI": mti}


_ABR_ATTRS = ("S_LENGUAGE", "N_COLOR", "N_TIME_ZONE", "S_DEVICE_MODEL", "S_DEVICE_NAME", "S_DEVICE_MAKER",
              "N_GPS_COMPATIBLE", "S_VERSION_OS", "S_DEVICE_ID", "S_RANDOM_SEED")


def abr(cli: str, ts: datetime, device: str | None = "DEV_1", seed: str | None = None,
        evento: str | None = None) -> dict:
    """device e seed None: notificação sem nenhum atributo (inválida para V5)."""
    row = {c: None for c in _ABR_ATTRS}
    if device is not None:
        row |= {"S_LENGUAGE": "es", "S_DEVICE_MODEL": "MODEL_X", "S_DEVICE_ID": device}
    row["S_RANDOM_SEED"] = seed
    return {"N_ID_CUSTOMER": cli, "D_TRANSMISSION_DATE_AND_TIME": ts,
            "FECHA_INFORMACION": ts.replace(hour=0, minute=0, second=0)} | row | {"ID_EVENTO": evento}


def vu(cli: str, ts: datetime, tipo: int | None, evento: str | None = None) -> dict:
    return {"N_ID_CUSTOMER": cli, "D_TRANSMISSION_DATE_AND_TIME": ts,
            "FECHA_INFORMACION": ts.replace(hour=0, minute=0, second=0), "S_IP_ADDRESS": "10.0.0.1",
            "S_CHANNEL_NAME": "APP", "N_TRANSACTION_TYPE": tipo, "ID_EVENTO": evento}


def t0(cli: str, ts: datetime, monto: object = Decimal(50_000), mcc: int | None = 5411,
       com: str | None = "COM_A") -> dict:
    return {"N_ID_CUSTOMER": cli, "D_TRANSMISSION_DATE_AND_TIME": ts, "F_AMOUNT_TRANSACTION": monto,
            "N_MERCHANT_TYPE": mcc, "S_CARD_ACCEPT_EXT_ID_CODE": com}


def _ago(s: int, base: datetime = T0_TS) -> datetime:
    return base - timedelta(seconds=s)


# ------------------------------------------------------------------ cenários

def excel() -> Scenario:
    f = excel_fixture()
    by_id = {r["ID_TRX"]: r for r in f["trx"]}
    s = Scenario("excel", list(f["trx"]), list(f["abr"]), list(f["vu"]))
    s.checks = [Check(f"excel/{c['caso']}", by_id[c["id_trx"]], c["id_trx"], c["esperado"]) for c in f["casos"]]
    return s


def bordas() -> Scenario:
    """Para cada janela N: eventos em N−1, N e N+1 segundos antes do T0, mais um no mesmo segundo e um depois."""
    s = Scenario("bordas")
    ids = _Ids("EQB")
    for n in _WINDOWS:
        cli = f"EQ_BORDA_{n}"
        for k, d in enumerate((n - 1, n, n + 1, 0, -1, 7_948_799)):
            m = Decimal(1000 * (k + 1))
            s.trx.append(trx(ids(), cli, _ago(d), m))
            s.trx.append(trx(ids(), cli, _ago(d), m + 1, resp="05"))
            s.trx.append(trx(ids(), cli, _ago(d), m + 2, entry=81, com="COM_B"))
            s.abr.append(abr(cli, _ago(d), f"DEV_{k}"))
            s.vu.append(vu(cli, _ago(d), 999901 if k % 2 == 0 else 999910))
        s.checks.append(Check(f"bordas/{n}s", t0(cli, T0_TS)))
    return s


def faixas() -> Scenario:
    """V9 (madrugada 00–05) e V8 (diurno 06–14) nas trocas de hora, incluindo 22h e 23h, que são tarde-noche."""
    s = Scenario("faixas")
    ids = _Ids("EQH")
    cli = "EQ_FAIXAS"
    dia = T0_TS.replace(hour=0) - timedelta(days=1)
    for k, hms in enumerate(((0, 0, 0), (5, 59, 59), (6, 0, 0), (14, 59, 59), (15, 0, 0), (22, 0, 0), (23, 0, 0),
                             (23, 59, 59))):
        ts = dia.replace(hour=hms[0], minute=hms[1], second=hms[2])
        s.trx.append(trx(ids(), cli, ts, Decimal(10_000 * (k + 1))))
        s.trx.append(trx(ids(), cli, ts, Decimal(1_000 * (k + 1)), resp="51"))
        s.trx.append(trx(ids(), cli, ts, Decimal(500 * (k + 1)), resp=None, nombre="WEBPAY.CL", pais=840))
    for h in (3, 9, 13):  # T0 de madrugada, de dia e no limite das 24 h
        s.checks.append(Check(f"faixas/T0_{h}h", t0(cli, T0_TS.replace(hour=h))))
    return s


def vu_casos() -> Scenario:
    s = Scenario("vu")
    ids = _Ids("EQV")
    for cli in ("EQ_VU_VAZIO", "EQ_VU_SEM_CPASS", "EQ_CPASS_T0", "EQ_VU_FUTURO"):
        s.trx.append(trx(ids(), cli, _ago(100)))
    s.vu += [vu("EQ_VU_SEM_CPASS", _ago(d), 999910) for d in (0, 10, 5000)]
    s.vu += [vu("EQ_CPASS_T0", _ago(0), 999903), vu("EQ_CPASS_T0", _ago(10), 999910), vu("EQ_CPASS_T0", _ago(20), None)]
    s.vu += [vu("EQ_VU_FUTURO", _ago(-1), 999901), vu("EQ_VU_FUTURO", _ago(-3600), 999903)]
    s.checks += [Check(f"vu/{c}", t0(c, T0_TS)) for c in ("EQ_VU_VAZIO", "EQ_VU_SEM_CPASS", "EQ_CPASS_T0",
                                                           "EQ_VU_FUTURO")]
    return s


def montos() -> Scenario:
    """Ausência de linhas × monto zero × monto nulo, e MCC/comercio nulos no T0 (nulo casa com nulo)."""
    s = Scenario("montos")
    ids = _Ids("EQM")
    s.trx += [trx(ids(), "EQ_MONTO_ZERO", _ago(d), Decimal(0)) for d in (10, 4000, 90_000)]
    s.trx += [trx(ids(), "EQ_MONTO_ZERO", _ago(d), Decimal(0), resp="05") for d in (20, 5000)]
    s.trx += [trx(ids(), "EQ_MONTO_NULO", _ago(d), None) for d in (10, 4000, 90_000)]
    s.trx += [trx(ids(), "EQ_MONTO_NULO", _ago(d), None, resp="05", entry=9) for d in (20, 5000)]
    s.trx += [trx(ids(), "EQ_MISTO", _ago(10), None), trx(ids(), "EQ_MISTO", _ago(20), Decimal("1234.56")),
              trx(ids(), "EQ_MISTO", _ago(30), Decimal("-100.00")), trx(ids(), "EQ_MISTO", _ago(40), None, resp="05"),
              trx(ids(), "EQ_MISTO", _ago(50), None, mcc=None, com=None),
              trx(ids(), "EQ_MISTO", _ago(60), Decimal(700), mcc=None, com=None),
              trx(ids(), "EQ_MISTO", _ago(70), Decimal(900), origen="otro"),
              trx(ids(), "EQ_MISTO", _ago(80), Decimal(900), mti=210),
              trx(ids(), "EQ_MISTO", _ago(90), Decimal(900), pcode=1000)]
    s.trx += [trx(ids(), "EQ_SO_FORA_BASE", _ago(d), Decimal(900), origen="otro") for d in (10, 4000)]
    for cli in ("EQ_SEM_NADA", "EQ_MONTO_ZERO", "EQ_MONTO_NULO", "EQ_MISTO", "EQ_SO_FORA_BASE"):
        s.checks.append(Check(f"montos/{cli}", t0(cli, T0_TS)))
    s.checks += [Check("montos/EQ_MISTO_t0_nulos", t0("EQ_MISTO", T0_TS, None, None, None)),
                 Check("montos/EQ_MONTO_ZERO_t0_zero", t0("EQ_MONTO_ZERO", T0_TS, Decimal(0)))]
    return s


def abr_casos() -> Scenario:
    """ABR: dois dispositivos no mesmo segundo contam uma vez, notificação sem atributos não conta."""
    s = Scenario("abr")
    cli = "EQ_ABR"
    s.trx.append(trx("EQA1", cli, _ago(100)))
    s.abr += [abr(cli, _ago(10), "DEV_1"), abr(cli, _ago(10), "DEV_2"), abr(cli, _ago(20), None),
              abr(cli, _ago(30), None, seed="S"), abr(cli, _ago(4000), "DEV_1"), abr(cli, _ago(4000), "DEV_1"),
              abr(cli, _ago(0), "DEV_1"), abr(cli, _ago(-5), "DEV_1")]
    s.abr += [abr("EQ_ABR_INVALIDO", _ago(10), None), abr("EQ_ABR_INVALIDO", _ago(5000), None)]
    s.checks += [Check("abr/EQ_ABR", t0(cli, T0_TS)), Check("abr/EQ_ABR_INVALIDO", t0("EQ_ABR_INVALIDO", T0_TS))]
    return s


def varios_t0() -> Scenario:
    """Vários T0 do mesmo cliente, cada um ingerido como TRX (confere também o GET por ID_TRX)."""
    s = Scenario("varios_t0")
    rnd = random.Random(7)
    cli = "EQ_VARIOS"
    for i in range(40):
        s.trx.append(trx(f"EQT{i}", cli, _ago(rnd.randint(1, 3 * 86_400)), Decimal(rnd.randint(1, 900) * 100),
                         resp=rnd.choice(["00", "00", "05"]), mcc=rnd.choice([5411, 5812]),
                         com=rnd.choice(["COM_A", "COM_B"]), entry=rnd.choice([9, 81])))
    for t in rnd.sample(s.trx, 4):
        s.checks.append(Check(f"varios_t0/{t['ID_TRX']}", t, t["ID_TRX"]))
    return s


_OFFSETS = [0, 1, 2, 299, 300, 301, 3599, 3600, 3601, 10_799, 10_800, 10_801, 86_399, 86_400, 86_401,
            7_948_799, 7_948_800, 7_948_801, -1, -60]


def aleatorio(seed: int = 42, clients: int = 25) -> Scenario:
    rng = random.Random(seed)
    s = Scenario("aleatorio")
    ids, evs = _Ids("EQR"), _Ids("EQE")
    for i in range(clients):
        cli = f"EQ_ALEA_{i}"
        base = T0_TS + timedelta(seconds=rng.randint(0, 86_400 * 5))
        for _ in range(60):
            off = rng.choice(_OFFSETS) if rng.random() < 0.5 else rng.randint(-100, 8_000_000)
            s.trx.append(trx(
                ids(), cli, _ago(off, base),
                rng.choice([None, Decimal(0), Decimal("-50.00")] + [Decimal(rng.randint(1, 500_000)) / 100] * 6),
                rng.choice([None, "00", "00", "00", "05", "51"]), rng.choice([3000, 2000, 6000, 1000, None]),
                rng.choice([9, 1, 900, None, 81]), rng.choice([152, 152, 840, None]),
                rng.choice([None, "COMERCIO", "WEBPAY.CL X", "webpay"]), rng.choice([5411, 5812, None]),
                rng.choice(["COM_A", "COM_B", None]), rng.choice(["atmpos_trxs"] * 5 + ["otro", None]),
                rng.choice([200] * 5 + [210, None])))
        for _ in range(15):
            ev = evs() if rng.random() < 0.5 else None
            s.abr.append(abr(cli, _ago(rng.choice(_OFFSETS + [rng.randint(0, 8_000_000)]), base),
                             rng.choice(["D1", "D2", None]), rng.choice(["S", None]), ev))
            s.vu.append(vu(cli, _ago(rng.choice(_OFFSETS), base), rng.choice([999901, 999903, 999910, None]),
                           evs() if rng.random() < 0.5 else None))
        s.checks.append(Check(f"aleatorio/{cli}", t0(cli, base, Decimal(rng.choice([1000, 0])),
                                                     rng.choice([5411, None]), rng.choice(["COM_A", None]))))
    return s


def all_scenarios(seed: int = 42, clients: int = 25) -> list[Scenario]:
    return [excel(), bordas(), faixas(), vu_casos(), montos(), abr_casos(), varios_t0(), aleatorio(seed, clients)]


def future_events(s: Scenario) -> tuple[list[dict], list[dict], list[dict]]:
    """Eventos de cada cliente posteriores ao seu último T0: não podem mudar nenhuma leitura já feita."""
    last: dict[str, datetime] = {}
    for c in s.checks:
        ts = _dt(c.t0["D_TRANSMISSION_DATE_AND_TIME"])
        cli = c.t0["N_ID_CUSTOMER"]
        last[cli] = max(last.get(cli, ts), ts)
    t, a, v = [], [], []
    for i, (cli, ts) in enumerate(sorted(last.items())):
        for d in (1, 2, 86_400):
            when = ts + timedelta(seconds=d)
            t.append(trx(f"EQFUT_{s.nome}_{i}_{d}", cli, when, Decimal(999_999)))
            a.append(abr(cli, when, "DEV_FUT"))
            v.append(vu(cli, when, 999901))
    return t, a, v


def _dt(v: object) -> datetime:
    return v if isinstance(v, datetime) else datetime.fromisoformat(str(v))
