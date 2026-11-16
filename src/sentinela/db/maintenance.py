"""Operações fora do caminho quente: reset, carga em massa, retenção, leitura síncrona e plano."""

from __future__ import annotations

import re
from collections.abc import Iterable
from datetime import date, datetime, timedelta

import oracledb

from sentinela.db import read_sql, rows
from sentinela.db.rows import Kind, LoteResult
from sentinela.domain import variables
from sentinela.domain.events import T0

RETENTION_DAYS = 93
BATCH = 5_000
_HIGH_VALUE = re.compile(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")


class Maintenance:
    def __init__(self, conn: oracledb.Connection):
        self.conn = conn

    def reset(self) -> None:
        with self.conn.cursor() as cur:
            for t in rows.TABLES:
                cur.execute(f"TRUNCATE TABLE {t} DROP ALL STORAGE")

    def count(self, table: str) -> int:
        if table not in rows.TABLES:
            raise ValueError(table)
        with self.conn.cursor() as cur:
            cur.execute(f"SELECT COUNT(*) FROM {table}")
            return int(cur.fetchone()[0])

    def bulk_insert(self, kind: Kind, events: Iterable) -> LoteResult:
        """Insere em lotes, com commit por lote. Duplicatas da chave única são contadas e ignoradas."""
        total = novas = dup = 0
        erros: list[dict] = []
        with self.conn.cursor() as cur:
            batch: list[tuple] = []

            def flush() -> None:
                nonlocal total, novas, dup
                cur.setinputsizes(*kind.types)
                cur.executemany(kind.sql, batch, batcherrors=True)
                r = rows.lote_result(len(batch), cur.getbatcherrors())
                self.conn.commit()
                erros.extend({"indice": e["indice"] + total, "erro": e["erro"]} for e in r.erros)
                total += r.recebidas
                novas += r.novas
                dup += r.duplicadas
                batch.clear()

            for e in events:
                batch.append(kind.binds(e))  # type: ignore[operator]
                if len(batch) == BATCH:
                    flush()
            if batch:
                flush()
        return LoteResult(total, novas, dup, erros)

    def drop_old_partitions(self, today: date) -> list[str]:
        """Remove as partições diárias inteiramente anteriores a today − RETENTION_DAYS (hora local)."""
        cutoff = datetime.combine(today - timedelta(days=RETENTION_DAYS), datetime.min.time())
        dropped = []
        with self.conn.cursor() as cur:
            for table in rows.TABLES:
                cur.execute("SELECT partition_name, high_value FROM user_tab_partitions"
                            " WHERE table_name = :t AND partition_position > 1 ORDER BY partition_position",
                            t=table.upper())
                for name, high in cur.fetchall():
                    m = _HIGH_VALUE.search(high or "")
                    if m and datetime.fromisoformat(m.group(1)) <= cutoff:
                        cur.execute(f'ALTER TABLE {table} DROP PARTITION "{name}" UPDATE GLOBAL INDEXES')
                        dropped.append(f"{table}.{name}")
        return dropped

    def gather_stats(self) -> None:
        with self.conn.cursor() as cur:
            cur.execute("BEGIN FOR t IN (SELECT column_value n FROM TABLE(sys.odcivarchar2list('TRX','ABR','VU')))"
                        " LOOP DBMS_STATS.GATHER_TABLE_STATS(USER, t.n); END LOOP; END;")

    def features(self, t0: T0) -> dict[str, variables.Value]:
        """A mesma leitura da API, síncrona: usada pela conferência do plano e pelos testes."""
        with self.conn.cursor() as cur:
            cur.setinputsizes(**read_sql.INPUT_TYPES)
            cur.execute(read_sql.SQL, read_sql.binds(t0))
            return variables.compute(read_sql.parse(cur.fetchone()), t0.monto)

    def read_plan(self) -> list[str]:
        with self.conn.cursor() as cur:
            cur.execute("SELECT sql_id, child_number FROM v$sql WHERE sql_text LIKE :p"
                        " AND parsing_schema_name = USER ORDER BY last_active_time DESC FETCH FIRST 1 ROWS ONLY",
                        p=read_sql.PREFIX + "%")
            row = cur.fetchone()
            if row is None:
                raise RuntimeError("SQL de leitura não encontrado em V$SQL; execute uma leitura antes")
            cur.execute("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(:1, :2, 'BASIC'))",
                        [row[0], int(row[1])])
            return [r[0] for r in cur]
