"""Operações fora do caminho quente: Job diário, retenção, reset, carga em massa, leitura síncrona e plano."""

from __future__ import annotations

import re
from collections.abc import Iterable, Iterator
from datetime import date, datetime, timedelta, tzinfo

import oracledb

from sentinela.db import read_sql
from sentinela.db.convert import cents, naive_utc
from sentinela.domain.agg import FeatureVector, WindowAgg
from sentinela.domain.windows import day_start, limits

TX_RETENTION_DAYS = 93
BATCH = 5_000
COMMIT_EVERY = 50_000
_HIGH_VALUE = re.compile(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")

_RECALC = ("SELECT account_id, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
           " FROM tx WHERE ts >= :ini AND ts < :fim GROUP BY account_id")
_STORED = "SELECT account_id, cnt, sum_amount, min_amount, max_amount FROM daily_agg WHERE dia = :dia"

# Valores em centavos inteiros; a divisão por 100 é exata no NUMBER do Oracle.
_INSERT_TX = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (:1, :2, :3, :4 / 100)"
_INSERT_DAILY = ("INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount)"
                 " VALUES (:1, :2, :3, :4 / 100, :5 / 100, :6 / 100)")


def _dia(d: date) -> datetime:
    return datetime(d.year, d.month, d.day)


class Maintenance:
    def __init__(self, conn: oracledb.Connection, zone: tzinfo):
        self.conn = conn
        self.zone = zone

    def _bounds(self, d: date) -> dict:
        return {"ini": naive_utc(day_start(d, self.zone)), "fim": naive_utc(day_start(d + timedelta(days=1), self.zone))}

    def _scalar(self, sql: str, **binds) -> int:
        with self.conn.cursor() as cur:
            cur.execute(sql, binds)
            return int(cur.fetchone()[0])

    def reset(self) -> None:
        with self.conn.cursor() as cur:
            cur.execute("TRUNCATE TABLE tx DROP ALL STORAGE")
            cur.execute("TRUNCATE TABLE daily_agg")

    def is_empty(self) -> bool:
        return self._scalar("SELECT (SELECT COUNT(*) FROM (SELECT 1 FROM tx FETCH FIRST 1 ROWS ONLY))"
                            " + (SELECT COUNT(*) FROM (SELECT 1 FROM daily_agg FETCH FIRST 1 ROWS ONLY)) FROM dual") == 0

    def count_tx(self) -> int:
        return self._scalar("SELECT COUNT(*) FROM tx")

    def close_day(self, d: date) -> int:
        """Recalcula DAILY_AGG do dia d a partir de TX, numa transação. Idempotente."""
        with self.conn.cursor() as cur:
            try:
                cur.execute("DELETE FROM daily_agg WHERE dia = :dia", dia=_dia(d))
                cur.setinputsizes(dia=oracledb.DB_TYPE_DATE, ini=oracledb.DB_TYPE_TIMESTAMP,
                                  fim=oracledb.DB_TYPE_TIMESTAMP)
                cur.execute("INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount)"
                            " SELECT account_id, :dia, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
                            " FROM tx WHERE ts >= :ini AND ts < :fim GROUP BY account_id",
                            dia=_dia(d), **self._bounds(d))
                n = cur.rowcount
                self.conn.commit()
                return n
            except oracledb.Error:
                self.conn.rollback()
                raise

    def check_day(self, d: date) -> int:
        """Linhas divergentes entre DAILY_AGG e o recálculo direto sobre TX (0 = igual)."""
        sql = (f"SELECT COUNT(*) FROM (({_STORED} MINUS {_RECALC}) UNION ALL ({_RECALC} MINUS {_STORED}))")
        return self._scalar(sql, dia=_dia(d), **self._bounds(d))

    def daily_rows(self, d: date) -> list[tuple[int, WindowAgg]]:
        with self.conn.cursor() as cur:
            cur.execute(_STORED + " ORDER BY account_id", dia=_dia(d))
            return [(int(r[0]), WindowAgg(int(r[1]), cents(r[2]), cents(r[3]), cents(r[4]))) for r in cur]

    def drop_old_partitions(self, today: date) -> list[str]:
        cutoff = naive_utc(day_start(today - timedelta(days=TX_RETENTION_DAYS), self.zone))
        with self.conn.cursor() as cur:
            cur.execute("SELECT partition_name, high_value FROM user_tab_partitions"
                        " WHERE table_name = 'TX' AND partition_position > 1 ORDER BY partition_position")
            candidates = []
            for name, high in cur.fetchall():
                m = _HIGH_VALUE.search(high or "")
                if m and datetime.fromisoformat(m.group(1)) <= cutoff:
                    candidates.append(name)
            for name in candidates:
                cur.execute(f'ALTER TABLE tx DROP PARTITION "{name}" UPDATE GLOBAL INDEXES')
        return candidates

    def gather_stats(self) -> None:
        with self.conn.cursor() as cur:
            cur.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'TX');"
                        " DBMS_STATS.GATHER_TABLE_STATS(USER, 'DAILY_AGG'); END;")

    def bulk_insert_tx(self, rows: Iterable[tuple[int, int, datetime, int]]) -> int:
        """rows: (tx_id, account_id, ts naive UTC, centavos)."""
        return self._bulk(_INSERT_TX, (None, None, oracledb.DB_TYPE_TIMESTAMP, None), iter(rows))

    def bulk_insert_daily(self, d: date, rows: Iterable[tuple[int, int, int, int, int]]) -> int:
        """rows: (account_id, cnt, soma, mínimo, máximo), valores em centavos."""
        dia = _dia(d)
        return self._bulk(_INSERT_DAILY, (None, oracledb.DB_TYPE_DATE, None, None, None, None),
                          ((acc, dia, c, s, mn, mx) for acc, c, s, mn, mx in rows))

    def _bulk(self, sql: str, types: tuple, rows: Iterator[tuple]) -> int:
        n = 0
        since_commit = 0
        with self.conn.cursor() as cur:
            try:
                batch: list[tuple] = []
                for r in rows:
                    batch.append(r)
                    if len(batch) == BATCH:
                        cur.setinputsizes(*types)
                        cur.executemany(sql, batch)
                        n += len(batch)
                        since_commit += len(batch)
                        batch = []
                        if since_commit >= COMMIT_EVERY:
                            self.conn.commit()
                            since_commit = 0
                if batch:
                    cur.setinputsizes(*types)
                    cur.executemany(sql, batch)
                    n += len(batch)
                self.conn.commit()
            except oracledb.Error:
                self.conn.rollback()
                raise
        return n

    def read(self, account_id: int, now: datetime) -> FeatureVector:
        """A mesma leitura da API, síncrona: usada pela conferência do plano e pelos testes."""
        with self.conn.cursor() as cur:
            cur.setinputsizes(*read_sql.INPUT_TYPES)
            cur.execute(read_sql.SQL, read_sql.binds(account_id, limits(now, self.zone)))
            return read_sql.parse(cur.fetchone())

    def read_plan(self) -> list[str]:
        with self.conn.cursor() as cur:
            cur.execute("SELECT sql_id, child_number FROM v$sql WHERE sql_text LIKE :p"
                        " AND parsing_schema_name = USER ORDER BY last_active_time DESC FETCH FIRST 1 ROWS ONLY",
                        p=read_sql.PREFIX + "%")
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("SQL de leitura não encontrado em V$SQL; execute uma leitura antes")
            cur.execute("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(:1, :2, 'BASIC +PARTITION'))",
                        [row[0], int(row[1])])
            return [r[0] for r in cur]
