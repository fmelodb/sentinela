"""Ingest API e query API das 10 variáveis do modelo XGBoost de fraude.

POST /v1/trx            grava uma TRX (201 nova, 200 ID_TRX duplicado)
POST /v1/trx/lote       lista de TRX; devolve recebidas, novas, duplicadas e erros por índice
POST /v1/abr, /v1/abr/lote, /v1/vu, /v1/vu/lote
                        idem para ABR e VU; só há duplicata quando o corpo traz ID_EVENTO
POST /v1/features       as 10 variáveis da compra enviada no corpo (T0)
GET  /v1/features/{id}  as 10 variáveis de uma TRX já ingerida, com T0 = ela
GET  /health            ping no banco

Corpos com os nomes de coluna das fontes (sentinela.api.models). Toda resposta que toca o banco traz
Server-Timing: db;dur=<ms>, o tempo da chamada ao Oracle medido aqui.
"""

from __future__ import annotations

import logging
import time
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import tzinfo
from typing import Annotated, Protocol

import oracledb
import orjson
from fastapi import Body, FastAPI, Path, Request
from fastapi.responses import Response

from sentinela.api.models import MAX_LOTE, AbrIn, T0In, TrxIn, VuIn
from sentinela.db.convert import iso_local
from sentinela.db.rows import LoteResult
from sentinela.domain import variables
from sentinela.domain.events import T0, Abr, Trx, Vu

log = logging.getLogger("sentinela.api")


class Store(Protocol):
    zone: tzinfo

    async def ingest_trx(self, t: Trx) -> bool: ...
    async def ingest_abr(self, a: Abr) -> bool: ...
    async def ingest_vu(self, v: Vu) -> bool: ...
    async def ingest_trx_lote(self, ts: list[Trx]) -> LoteResult: ...
    async def ingest_abr_lote(self, a: list[Abr]) -> LoteResult: ...
    async def ingest_vu_lote(self, v: list[Vu]) -> LoteResult: ...
    async def features(self, t0: T0) -> dict[str, variables.Value]: ...
    async def t0_by_id(self, id_trx: str) -> T0 | None: ...
    async def ping(self) -> None: ...
    async def close(self) -> None: ...


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


def _features_body(t0: T0, values: dict[str, variables.Value]) -> dict:
    return {"ID_TRX": t0.id_trx, "N_ID_CUSTOMER": t0.cliente, "T0": iso_local(t0.ts), "versao": variables.VERSAO,
            "variaveis": values}


def create_app(store_factory: Callable[[], object] = _open_store) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.store = await store_factory()  # type: ignore[misc]
        try:
            yield
        finally:
            await app.state.store.close()

    app = FastAPI(title="sentinela", version="3.0.0", lifespan=lifespan,
                  description="Feature store das 10 variáveis do modelo XGBoost de fraude "
                              f"(definição {variables.VERSAO}).")

    def store(request: Request) -> Store:
        return request.app.state.store

    @app.exception_handler(oracledb.Error)
    async def db_error(request: Request, exc: oracledb.Error) -> Response:
        log.warning("erro do Oracle em %s: %s", request.url.path, exc)
        return _json({"erro": "banco indisponível", "detalhe": str(exc).splitlines()[0]}, 503)

    async def one(call, event, key: dict) -> Response:
        t0 = time.perf_counter_ns()
        new = await call(event)
        db_ns = time.perf_counter_ns() - t0
        return _json(key | {"duplicada": not new}, 201 if new else 200, db_ns)

    async def many(call, events: list) -> Response:
        t0 = time.perf_counter_ns()
        r = await call(events)
        return _json(r.to_json(), 200, time.perf_counter_ns() - t0)

    @app.post("/v1/trx")
    async def ingest_trx(body: TrxIn, request: Request) -> Response:
        s = store(request)
        return await one(s.ingest_trx, body.to_domain(s.zone), {"ID_TRX": body.ID_TRX})

    @app.post("/v1/trx/lote")
    async def ingest_trx_lote(body: Annotated[list[TrxIn], Body(max_length=MAX_LOTE)], request: Request) -> Response:
        s = store(request)
        return await many(s.ingest_trx_lote, [b.to_domain(s.zone) for b in body])

    @app.post("/v1/abr")
    async def ingest_abr(body: AbrIn, request: Request) -> Response:
        s = store(request)
        return await one(s.ingest_abr, body.to_domain(s.zone), {"ID_EVENTO": body.ID_EVENTO})

    @app.post("/v1/abr/lote")
    async def ingest_abr_lote(body: Annotated[list[AbrIn], Body(max_length=MAX_LOTE)], request: Request) -> Response:
        s = store(request)
        return await many(s.ingest_abr_lote, [b.to_domain(s.zone) for b in body])

    @app.post("/v1/vu")
    async def ingest_vu(body: VuIn, request: Request) -> Response:
        s = store(request)
        return await one(s.ingest_vu, body.to_domain(s.zone), {"ID_EVENTO": body.ID_EVENTO})

    @app.post("/v1/vu/lote")
    async def ingest_vu_lote(body: Annotated[list[VuIn], Body(max_length=MAX_LOTE)], request: Request) -> Response:
        s = store(request)
        return await many(s.ingest_vu_lote, [b.to_domain(s.zone) for b in body])

    @app.post("/v1/features")
    async def features_post(body: T0In, request: Request) -> Response:
        s = store(request)
        t0 = body.to_domain(s.zone)
        start = time.perf_counter_ns()
        values = await s.features(t0)
        return _json(_features_body(t0, values), 200, time.perf_counter_ns() - start)

    @app.get("/v1/features/{id_trx}")
    async def features_get(request: Request, id_trx: Annotated[str, Path(min_length=1, max_length=64)]) -> Response:
        s = store(request)
        start = time.perf_counter_ns()
        t0 = await s.t0_by_id(id_trx)
        if t0 is None:
            return _json({"erro": f"ID_TRX não encontrado: {id_trx}"}, 404, time.perf_counter_ns() - start)
        values = await s.features(t0)
        return _json(_features_body(t0, values), 200, time.perf_counter_ns() - start)

    @app.get("/health")
    async def health(request: Request) -> Response:
        t0 = time.perf_counter_ns()
        await store(request).ping()
        return _json({"status": "ok", "versao": variables.VERSAO}, 200, time.perf_counter_ns() - t0)

    return app


app = create_app()
