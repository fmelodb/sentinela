"""Oracle como feature store no caminho quente da API: ingestão das três fontes (unitária e em lote) e a leitura
das 10 variáveis em um statement."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import tzinfo

import oracledb

from sentinela.db import read_sql, rows
from sentinela.db.rows import Kind, LoteResult
from sentinela.domain import variables
from sentinela.domain.events import T0, Abr, Trx, Vu


class AsyncStore:
    def __init__(self, pool: oracledb.AsyncConnectionPool, zone: tzinfo):
        self.pool = pool
        self.zone = zone

    async def _one(self, kind: Kind, event) -> bool:
        """True se gravou; False se a chave única já existia (idempotência)."""
        async with self.pool.acquire() as c:
            c.autocommit = True
            cur = c.cursor()
            cur.setinputsizes(*kind.types)
            try:
                await cur.execute(kind.sql, kind.binds(event))  # type: ignore[operator]
            except oracledb.IntegrityError as e:
                if rows.is_duplicate(e):
                    return False
                raise
            return True

    async def _many(self, kind: Kind, events: Sequence) -> LoteResult:
        if not events:
            return LoteResult(0, 0, 0, [])
        async with self.pool.acquire() as c:
            cur = c.cursor()
            cur.setinputsizes(*kind.types)
            await cur.executemany(kind.sql, [kind.binds(e) for e in events], batcherrors=True)  # type: ignore[operator]
            errors = cur.getbatcherrors()
            await c.commit()
        return rows.lote_result(len(events), errors)

    async def ingest_trx(self, t: Trx) -> bool:
        return await self._one(rows.TRX, t)

    async def ingest_abr(self, a: Abr) -> bool:
        return await self._one(rows.ABR, a)

    async def ingest_vu(self, v: Vu) -> bool:
        return await self._one(rows.VU, v)

    async def ingest_trx_lote(self, ts: Sequence[Trx]) -> LoteResult:
        return await self._many(rows.TRX, ts)

    async def ingest_abr_lote(self, a: Sequence[Abr]) -> LoteResult:
        return await self._many(rows.ABR, a)

    async def ingest_vu_lote(self, v: Sequence[Vu]) -> LoteResult:
        return await self._many(rows.VU, v)

    async def features(self, t0: T0) -> dict[str, variables.Value]:
        async with self.pool.acquire() as c:
            cur = c.cursor()
            cur.prefetchrows = 2
            cur.arraysize = 1
            cur.setinputsizes(**read_sql.INPUT_TYPES)
            await cur.execute(read_sql.SQL, read_sql.binds(t0))
            row = await cur.fetchone()
        return variables.compute(read_sql.parse(row), t0.monto)

    async def t0_by_id(self, id_trx: str) -> T0 | None:
        async with self.pool.acquire() as c:
            cur = c.cursor()
            await cur.execute(read_sql.T0_BY_ID, id=id_trx)
            r = await cur.fetchone()
        return None if r is None else T0(r[0], r[1], r[2], None if r[3] is None else int(r[3]), r[4], r[5])

    async def ping(self) -> None:
        async with self.pool.acquire() as c:
            await c.ping()

    async def close(self) -> None:
        await self.pool.close()
