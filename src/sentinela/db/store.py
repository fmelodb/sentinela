"""Oracle como feature store no caminho quente da API: ingestão idempotente e leitura em um statement."""

from __future__ import annotations

from datetime import datetime, tzinfo

import oracledb

from sentinela.db import read_sql
from sentinela.db.convert import amount, naive_utc
from sentinela.domain.agg import FeatureVector, Transaction
from sentinela.domain.windows import limits

INSERT = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (:1, :2, :3, :4)"
INSERT_TYPES = (None, None, oracledb.DB_TYPE_TIMESTAMP, None)
ORA_UNIQUE_CONSTRAINT = 1


def is_duplicate(e: oracledb.IntegrityError) -> bool:
    err = e.args[0] if e.args else None
    return getattr(err, "code", None) == ORA_UNIQUE_CONSTRAINT


def ingest_binds(tx: Transaction) -> list:
    return [tx.tx_id, tx.account_id, naive_utc(tx.ts), amount(tx.amount_cents)]


class AsyncStore:
    def __init__(self, pool: oracledb.AsyncConnectionPool, zone: tzinfo):
        self.pool = pool
        self.zone = zone

    async def ingest(self, tx: Transaction) -> bool:
        """True se a transação é nova; False se o tx_id já existia (idempotência)."""
        async with self.pool.acquire() as c:
            c.autocommit = True
            cur = c.cursor()
            cur.setinputsizes(*INSERT_TYPES)
            try:
                await cur.execute(INSERT, ingest_binds(tx))
            except oracledb.IntegrityError as e:
                if is_duplicate(e):
                    return False
                raise
            return True

    async def read(self, account_id: int, now: datetime) -> FeatureVector:
        async with self.pool.acquire() as c:
            cur = c.cursor()
            cur.prefetchrows = 2
            cur.arraysize = 1
            cur.setinputsizes(*read_sql.INPUT_TYPES)
            await cur.execute(read_sql.SQL, read_sql.binds(account_id, limits(now, self.zone)))
            row = await cur.fetchone()
        return read_sql.parse(row)

    async def ping(self) -> None:
        async with self.pool.acquire() as c:
            await c.ping()

    async def close(self) -> None:
        await self.pool.close()
