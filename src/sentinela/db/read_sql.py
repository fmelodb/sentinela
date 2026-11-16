"""Statement único da leitura das 10 variáveis para uma compra T0.

Três subconsultas de agregação, uma por fonte, em CROSS JOIN: cada uma devolve exatamente uma linha. Em TRX,
agregados condicionais por janela e filtro sobre as linhas base do cliente nos 92 dias antes de T0; em ABR,
segundos distintos de notificações válidas; em VU, total e CPASS. As razões e as regras de NULL ficam em
sentinela.domain.variables, sobre os agregados brutos.

Comparação de MCC e comercio com DECODE: nulo = nulo, como a chave "NULL" do Excel.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import datetime, timedelta

import oracledb

from sentinela.domain.events import T0
from sentinela.domain.variables import (FILTERS, SEC_1H, SEC_3H, SEC_5M, SEC_24H, SEC_92D, WINDOWS, Aggregates,
                                        FilterAgg)

# Início do SQL como o Oracle o guarda em V$SQL: usado para achar o cursor na conferência do plano.
PREFIX = "SELECT /* sentinela.leitura */"
HINTS = {"trx": "/*+ INDEX(x trx_cli_ts_ix) */", "abr": "/*+ INDEX(a abr_cli_ts_ix) */",
         "vu": "/*+ INDEX(v vu_cli_ts_ix) */"}

_W24, _W3H, _W5 = "x.ts >= :lo24", "x.ts >= :lo3h", "x.ts >= :lo5"
_MCC = "DECODE(x.mcc, :mcc, 1, 0) = 1"
_COM = "DECODE(x.comercio, :com, 1, 0) = 1"

WINDOW_SQL: dict[str, str] = {
    "n24": _W24, "n92": "1 = 1", "n3h": _W3H,
    "n24_mcc": f"{_W24} AND {_MCC}", "n92_mcc": _MCC, "n5_com": f"{_W5} AND {_COM}",
}
FILTER_SQL: dict[str, str] = {
    "f1_24": f"x.ap = 1 AND {_W24}",
    "f1_92": "x.ap = 1",
    "f2_24": f"x.ap = 1 AND {_MCC} AND {_W24}",
    "f2_92": f"x.ap = 1 AND {_MCC}",
    "f3": f"x.re = 1 AND {_W24}",
    "f4": f"x.ap = 1 AND {_W5} AND {_COM}",
    "f7": f"x.ap_aus = 1 AND {_W3H}",
    "f8": "x.re_npd = 1",
    "f9_24": f"x.ap_mad = 1 AND {_W24}",
    "f9_92": "x.ap_mad = 1",
}
assert tuple(WINDOW_SQL) == WINDOWS and tuple(FILTER_SQL) == FILTERS


def _build() -> str:
    cols = [f"COUNT(CASE WHEN {c} THEN 1 END) AS {k}" for k, c in WINDOW_SQL.items()]
    for k, c in FILTER_SQL.items():
        cols += [f"COUNT(CASE WHEN {c} THEN 1 END) AS {k}_cnt",
                 f"COUNT(CASE WHEN {c} AND x.monto IS NULL THEN 1 END) AS {k}_nnull",
                 f"SUM(CASE WHEN {c} THEN x.monto END) AS {k}_sum",
                 f"MAX(CASE WHEN {c} THEN x.monto END) AS {k}_max"]
    trx = (f"SELECT {HINTS['trx']} {', '.join(cols)} FROM trx x"
           " WHERE x.cliente = :cli AND x.ts >= :lo92 AND x.ts <= :hi AND x.base = 1")
    abr = (f"SELECT {HINTS['abr']} COUNT(DISTINCT CASE WHEN a.ts >= :lo1h THEN a.ts END) AS abr_1h,"
           " COUNT(DISTINCT a.ts) AS abr_92 FROM abr a"
           " WHERE a.cliente = :cli AND a.ts >= :lo92 AND a.ts <= :hi AND a.valido = 1")
    vu = (f"SELECT {HINTS['vu']} COUNT(*) AS vu_92, COUNT(CASE WHEN v.cpass = 1 THEN 1 END) AS cpass_92 FROM vu v"
          " WHERE v.cliente = :cli AND v.ts >= :lo92 AND v.ts <= :t0")
    return f"{PREFIX} t.*, a.*, v.* FROM ({trx}) t CROSS JOIN ({abr}) a CROSS JOIN ({vu}) v"


SQL = _build()
COLUMNS = len(WINDOWS) + 4 * len(FILTERS) + 4
_DATE_BINDS = ("t0", "hi", "lo5", "lo1h", "lo3h", "lo24", "lo92")
INPUT_TYPES: dict = {k: oracledb.DB_TYPE_DATE for k in _DATE_BINDS} | {
    "cli": oracledb.DB_TYPE_VARCHAR, "mcc": oracledb.DB_TYPE_NUMBER, "com": oracledb.DB_TYPE_VARCHAR}


def binds(t0: T0) -> dict:
    def lo(sec: int) -> datetime:
        return t0.ts - timedelta(seconds=sec)

    return {"cli": t0.cliente, "t0": t0.ts, "hi": lo(1), "lo5": lo(SEC_5M), "lo1h": lo(SEC_1H), "lo3h": lo(SEC_3H),
            "lo24": lo(SEC_24H), "lo92": lo(SEC_92D), "mcc": t0.mcc, "com": t0.comercio}


def parse(row: Sequence) -> Aggregates:
    if len(row) != COLUMNS:
        raise ValueError(f"linha com {len(row)} colunas; esperado {COLUMNS}")
    n = {k: int(row[i]) for i, k in enumerate(WINDOWS)}
    f = {}
    base = len(WINDOWS)
    for j, k in enumerate(FILTERS):
        c, nn, s, mx = row[base + 4 * j: base + 4 * j + 4]
        f[k] = FilterAgg(int(c), int(nn), s, mx)
    tail = [int(v) for v in row[base + 4 * len(FILTERS):]]
    return Aggregates(n, f, *tail)


T0_BY_ID = "SELECT cliente, ts, monto, mcc, comercio, id_trx FROM trx WHERE id_trx = :id"
