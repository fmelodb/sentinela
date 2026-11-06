"""Statement único da leitura (docs/leitura-oracle.md), gerado a partir do catálogo de janelas.

Agregados condicionais sobre TX para as 15 janelas deslizantes e o dia corrente, em CROSS JOIN com agregados
sobre DAILY_AGG para os dias fechados das 4 janelas de dias. Os limites entram como colunas da linha b; os
predicados de poda usam binds diretos. Resultado: sempre uma linha com 80 colunas.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date, datetime

import oracledb

from sentinela.db.convert import cents, naive_utc
from sentinela.domain.agg import EMPTY, FeatureVector, WindowAgg
from sentinela.domain.windows import DAYS, ROLLING, Limits

TODAY = "hoje"
# Início do SQL como o Oracle o guarda em V$SQL: usado para achar o cursor na conferência do plano.
PREFIX = "WITH b AS (SELECT :1 AS lo_1m"
COLUMNS = 4 * (len(ROLLING) + 1 + len(DAYS))


def _tx_aggregates(s: str, cond: str) -> str:
    return (f"COUNT(CASE WHEN {cond} THEN 1 END) AS cnt_{s}, "
            f"SUM(CASE WHEN {cond} THEN x.amount END) AS sum_{s}, "
            f"MIN(CASE WHEN {cond} THEN x.amount END) AS min_{s}, "
            f"MAX(CASE WHEN {cond} THEN x.amount END) AS max_{s}")


def _build() -> tuple[str, list]:
    n = 0
    types: list = []

    def bind(t) -> str:
        nonlocal n
        n += 1
        types.append(t)
        return f":{n}"

    lows = [w.suffix for w in ROLLING] + [TODAY] + [w.suffix for w in DAYS]
    b_cols = []
    for s in lows:
        t = oracledb.DB_TYPE_DATE if s in {w.suffix for w in DAYS} else oracledb.DB_TYPE_TIMESTAMP
        b_cols.append(f"{bind(t)} AS lo_{s}")
    tx_cols = [_tx_aggregates(w.suffix, f"x.ts > b.lo_{w.suffix}") for w in ROLLING]
    tx_cols.append(_tx_aggregates(TODAY, f"x.ts >= b.lo_{TODAY}"))
    tx_where = (f"x.account_id = {bind(None)} AND x.ts > {bind(oracledb.DB_TYPE_TIMESTAMP)}"
                f" AND x.ts <= {bind(oracledb.DB_TYPE_TIMESTAMP)}")
    d_cols = []
    for w in DAYS:
        s, cond = w.suffix, f"a.dia >= b.lo_{w.suffix}"
        d_cols.append(f"SUM(CASE WHEN {cond} THEN a.cnt END) AS cnt_{s}, "
                      f"SUM(CASE WHEN {cond} THEN a.sum_amount END) AS sum_{s}, "
                      f"MIN(CASE WHEN {cond} THEN a.min_amount END) AS min_{s}, "
                      f"MAX(CASE WHEN {cond} THEN a.max_amount END) AS max_{s}")
    d_where = (f"a.account_id = {bind(None)} AND a.dia >= {bind(oracledb.DB_TYPE_DATE)}"
               f" AND a.dia < {bind(oracledb.DB_TYPE_DATE)}")
    sql = (f"WITH b AS (SELECT {', '.join(b_cols)} FROM dual) SELECT t.*, d.* FROM ("
           f"SELECT {', '.join(tx_cols)} FROM tx x CROSS JOIN b WHERE {tx_where}) t CROSS JOIN ("
           f"SELECT {', '.join(d_cols)} FROM daily_agg a CROSS JOIN b WHERE {d_where}) d")
    return sql, types


SQL, INPUT_TYPES = _build()


def binds(account_id: int, lim: Limits) -> list[int | datetime | date]:
    out: list[int | datetime | date] = [naive_utc(lo) for lo in lim.rolling_lo]
    out.append(naive_utc(lim.today_start))
    out.extend(_as_datetime(d) for d in lim.closed_lo)
    out += [account_id, naive_utc(lim.oldest_rolling), naive_utc(lim.now),
            account_id, _as_datetime(lim.oldest_closed), _as_datetime(lim.today)]
    return out


def _as_datetime(d: date) -> datetime:
    # DATE do Oracle é dia + hora; o driver espera datetime para DB_TYPE_DATE.
    return datetime(d.year, d.month, d.day)


def _agg(row: Sequence, base: int) -> WindowAgg:
    cnt = row[base]
    if cnt is None or cnt == 0:
        return EMPTY
    return WindowAgg(int(cnt), cents(row[base + 1]), cents(row[base + 2]), cents(row[base + 3]))


def parse(row: Sequence) -> FeatureVector:
    """Linha de 80 colunas → vetor; janelas de dias = dias fechados + dia corrente."""
    if len(row) != COLUMNS:
        raise ValueError(f"linha com {len(row)} colunas; esperado {COLUMNS}")
    aggs = [_agg(row, 4 * i) for i in range(len(ROLLING))]
    today = _agg(row, 4 * len(ROLLING))
    base = 4 * (len(ROLLING) + 1)
    aggs += [_agg(row, base + 4 * j).plus(today) for j in range(len(DAYS))]
    return FeatureVector(tuple(aggs))
