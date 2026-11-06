"""Conexões com o Oracle em modo thin (sem Instant Client); no ATP, TLS pela connect string."""

from __future__ import annotations

import asyncio

import oracledb

from sentinela.config import Env
from sentinela.errors import StoreUnavailable

STMT_CACHE = 20


def connect(env: Env) -> oracledb.Connection:
    """Conexão síncrona para a CLI (schema, backfill, job diário, equivalência)."""
    env.require_oracle()
    try:
        return oracledb.connect(user=env.oracle_user, password=env.oracle_password, dsn=env.oracle_dsn,
                                stmtcachesize=STMT_CACHE)
    except oracledb.Error as e:
        raise StoreUnavailable(f"Oracle em {env.oracle_dsn}: {e}") from e


async def open_async_pool(env: Env, size: int) -> oracledb.AsyncConnectionPool:
    """Pool de tamanho fixo, cheio antes de a API aceitar requisições."""
    env.require_oracle()
    try:
        pool = oracledb.create_pool_async(user=env.oracle_user, password=env.oracle_password, dsn=env.oracle_dsn,
                                          min=size, max=size, increment=1, stmtcachesize=STMT_CACHE,
                                          getmode=oracledb.POOL_GETMODE_WAIT, ping_interval=-1)
        conns = await asyncio.gather(*(pool.acquire() for _ in range(size)))
        for c in conns:
            await c.ping()
        for c in conns:
            await pool.release(c)
        return pool
    except oracledb.Error as e:
        raise StoreUnavailable(f"Oracle em {env.oracle_dsn}: {e}") from e
