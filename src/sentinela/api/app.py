"""Ingest API e query API.

POST /v1/transacoes                      grava uma transação (201 nova, 200 duplicada)
GET  /v1/contas/{account_id}/features    vetor de 95 features em now (parâmetro opcional)
GET  /health                             ping no banco

Toda resposta que toca o banco traz Server-Timing: db;dur=<ms>, com o tempo da chamada ao Oracle medido aqui.
O simulador usa esse header para separar o custo do banco do custo do HTTP.
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import datetime
from decimal import Decimal
from typing import Annotated, Protocol

import oracledb
import orjson
from fastapi import FastAPI, Path, Query, Request
from fastapi.responses import Response
from pydantic import AwareDatetime, BaseModel, Field

from sentinela.db.convert import cents
from sentinela.domain.agg import FeatureVector, Transaction
from sentinela.domain.windows import UTC

log = logging.getLogger("sentinela.api")


class Store(Protocol):
    async def ingest(self, tx: Transaction) -> bool: ...
    async def read(self, account_id: int, now: datetime) -> FeatureVector: ...
    async def ping(self) -> None: ...
    async def close(self) -> None: ...


class TxIn(BaseModel):
    tx_id: int = Field(ge=1)
    account_id: int = Field(gt=0)
    ts: AwareDatetime
    amount: Decimal = Field(gt=0, max_digits=14, decimal_places=2)


def _json(body: object, status: int = 200, db_ns: int | None = None) -> Response:
    headers = None if db_ns is None else {"Server-Timing": f"db;dur={db_ns / 1e6:.3f}"}
    return Response(orjson.dumps(body), status_code=status, media_type="application/json", headers=headers)


async def _open_store() -> Store:
    from sentinela.config import Env
    from sentinela.db.pool import open_async_pool
    from sentinela.db.store import AsyncStore

    env = Env.from_environ()
    pool = await open_async_pool(env, env.db_pool_size)
    return AsyncStore(pool, env.zone)


def create_app(store_factory: Callable[[], object] = _open_store) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.store = await store_factory()  # type: ignore[misc]
        try:
            yield
        finally:
            await app.state.store.close()

    app = FastAPI(title="sentinela", version="2.0.0", lifespan=lifespan)

    @app.exception_handler(oracledb.Error)
    async def db_error(request: Request, exc: oracledb.Error) -> Response:
        log.warning("erro do Oracle em %s: %s", request.url.path, exc)
        return _json({"erro": "banco indisponível", "detalhe": str(exc).splitlines()[0]}, 503)

    @app.post("/v1/transacoes")
    async def ingest(tx: TxIn, request: Request) -> Response:
        t = Transaction(tx.tx_id, tx.account_id, tx.ts, cents(tx.amount))
        t0 = time.perf_counter_ns()
        new = await request.app.state.store.ingest(t)
        db_ns = time.perf_counter_ns() - t0
        return _json({"tx_id": tx.tx_id, "duplicada": not new}, 201 if new else 200, db_ns)

    @app.get("/v1/contas/{account_id}/features")
    async def features(request: Request, account_id: Annotated[int, Path(gt=0)],
                       now: Annotated[AwareDatetime | None, Query()] = None) -> Response:
        at = datetime.now(UTC) if now is None else now.astimezone(UTC)
        t0 = time.perf_counter_ns()
        vector = await request.app.state.store.read(account_id, at)
        db_ns = time.perf_counter_ns() - t0
        return _json({"account_id": account_id, "now": at.isoformat(), "features": vector.to_json()}, 200, db_ns)

    @app.get("/health")
    async def health(request: Request) -> Response:
        t0 = time.perf_counter_ns()
        await request.app.state.store.ping()
        return _json({"status": "ok"}, 200, time.perf_counter_ns() - t0)

    return app


app = create_app()
