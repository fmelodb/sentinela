"""Programas de carga contra a API:

  ingestor  posta um NDJSON de uma fonte (trx, abr ou vu) em /v1/<fonte>, um evento por chamada, à taxa alvo.
            Carimba D_TRANSMISSION_DATE_AND_TIME = agora ao despachar; com --replay usa o instante do arquivo.
  leitor    pede as 10 variáveis à taxa alvo: POST /v1/features com uma compra sintética (agora) e, numa fração
            --fracao-get, GET /v1/features/{ID_TRX} de uma TRX que o ingestor acabou de postar.
  carga     os ingestores das três fontes e o leitor ao mesmo tempo, em processos separados, com o mesmo --run.
"""

from __future__ import annotations

import asyncio
import multiprocessing as mp
import os
import platform
import re
import socket
import sys
import time
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import aiohttp
import orjson

from sentinela.errors import INVALID_RUN, OK, InvalidRun, StoreUnavailable, UsageError
from sentinela.sim import runner
from sentinela.sim.csvlog import CsvLog, ErrorReporter, update_meta, write_meta
from sentinela.sim.samplers import ClientSampler, monto, rng
from sentinela.sim.synth import FONTES, cliente_id, comercio_of, mcc_of

UTC = timezone.utc
_DB_DUR = re.compile(r"db;dur=([0-9.]+)")
JSON = {"Content-Type": "application/json"}
TS = "D_TRANSMISSION_DATE_AND_TIME"
# O GET escolhe uma TRX postada entre GET_LAG_S e GET_LAG_S + GET_WINDOW_S segundos atrás (estimado pelo TPS do
# ingestor de TRX): margem para o atraso entre os processos, que partem quase juntos.
GET_LAG_S = 5
GET_WINDOW_S = 60


@dataclass(frozen=True)
class LoadArgs:
    api_url: str
    tps: int
    duration: int
    warmup: int
    concurrency: int
    run: str
    log: Path | None = None


@dataclass(frozen=True, slots=True)
class Item:
    operation: str
    cliente: str
    path: str
    body: bytes | None  # None = GET


class HttpError(Exception):
    pass


def default_run() -> str:
    return datetime.now(UTC).strftime("%Y%m%d-%H%M%S")


def default_log(program: str, run: str) -> Path:
    return Path("./logs") / f"{program}-{run}.csv"


def now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def db_us(headers) -> int | None:
    m = _DB_DUR.search(headers.get("Server-Timing", ""))
    return None if m is None else round(float(m.group(1)) * 1000)


def _session(concurrency: int) -> aiohttp.ClientSession:
    return aiohttp.ClientSession(
        connector=aiohttp.TCPConnector(limit=concurrency, keepalive_timeout=60, ttl_dns_cache=None),
        timeout=aiohttp.ClientTimeout(total=10))


async def check_api(session: aiohttp.ClientSession, api_url: str) -> None:
    try:
        async with session.get(f"{api_url}/health") as r:
            if r.status != 200:
                raise StoreUnavailable(f"API em {api_url}: /health devolveu {r.status}")
    except aiohttp.ClientError as e:
        raise StoreUnavailable(f"API em {api_url}: {e}") from e


def _meta(program: str, a: LoadArgs) -> dict:
    return {"program": program, "run": a.run, "tps": a.tps, "duration": a.duration, "warmup": a.warmup,
            "concurrency": a.concurrency, "api_url": a.api_url, "start_epoch_ms": time.time_ns() // 1_000_000,
            "python": platform.python_version(), "host": f"{socket.gethostname()} ({os.cpu_count()} CPUs)"}


async def _drive(program: str, a: LoadArgs, source: Iterator[Item], meta: dict, ok_status: tuple[int, ...]):
    log = a.log or default_log(program, a.run)
    errors = ErrorReporter()
    async with _session(a.concurrency) as session:
        await check_api(session, a.api_url)

        async def call(it: Item) -> tuple[bool, int | None]:
            url = a.api_url + it.path
            req = session.get(url) if it.body is None else session.post(url, data=it.body, headers=JSON)
            async with req as resp:
                await resp.read()
                if resp.status not in ok_status:
                    raise HttpError(f"{it.operation} {resp.status}")
                return True, db_us(resp.headers)

        with CsvLog(log, program) as csv:
            write_meta(log, _meta(program, a) | meta)
            r = await runner.run(source, runner.Step(lambda it: it.operation, call, lambda it: it.cliente),
                                 a.tps, a.duration, a.warmup, a.concurrency, csv, errors)
    update_meta(log, measured_starts=r.measured_starts, late_starts=r.late_starts,
                max_start_lag_ms=r.max_start_lag_ms, late_threshold_ms=runner.LATE_THRESHOLD_MS,
                end_epoch_ms=time.time_ns() // 1_000_000)
    print(f"{program}: fila após o warmup: {r.late_starts} de {r.measured_starts} início(s) com atraso > "
          f"{runner.LATE_THRESHOLD_MS} ms; atraso máximo {r.max_start_lag_ms} ms", file=sys.stderr)
    errors.summary()
    print(f"{program}: {r.dispatched} chamadas, {errors.total} erro(s) -> {log}", file=sys.stderr)
    return r


def _lines(path: Path) -> list[bytes]:
    if not path.exists():
        raise UsageError(f"dataset não encontrado: {path}; gere com: sentinela gerador")
    with path.open("rb") as f:
        return [line for line in f if line.strip()]


async def ingestor(a: LoadArgs, fonte: str, dataset_path: Path, replay: bool) -> int:
    if fonte not in FONTES:
        raise UsageError(f"--fonte precisa ser trx, abr ou vu: {fonte}")
    lines = _lines(dataset_path)
    if a.tps * a.duration > len(lines):
        print(f"aviso: {dataset_path} com {len(lines)} eventos não cobre tps × duration = {a.tps * a.duration}",
              file=sys.stderr)

    def source() -> Iterator[Item]:
        for line in lines:
            o = orjson.loads(line)
            if not replay:
                o[TS] = now_iso()
            yield Item("ingest", o["N_ID_CUSTOMER"], f"/v1/{fonte}", orjson.dumps(o))

    program = f"ingestor_{fonte}"
    r = await _drive(program, a, source(), {"fonte": fonte, "dataset": str(dataset_path), "replay": replay},
                     (200, 201))
    if r.source_exhausted:
        print(f"rodada inválida: {dataset_path} acabou antes de --duration; gere com mais eventos", file=sys.stderr)
        return INVALID_RUN
    return OK


def trx_ids(path: Path) -> list[tuple[str, str]]:
    """(ID_TRX, cliente) na ordem do arquivo, que é a ordem em que o ingestor posta."""
    out = []
    for line in _lines(path):
        o = orjson.loads(line)
        out.append((str(o["ID_TRX"]), str(o["N_ID_CUSTOMER"])))
    return out


async def leitor(a: LoadArgs, clients: int, seed: int, distribution: str, fracao_get: float,
                 trx_dataset: Path | None, tps_trx: int) -> int:
    if not 0 <= fracao_get <= 1:
        raise UsageError(f"--fracao-get precisa estar entre 0 e 1: {fracao_get}")
    ids = trx_ids(trx_dataset) if fracao_get > 0 and trx_dataset is not None and tps_trx > 0 else []
    sampler = ClientSampler(clients, distribution, rng(seed, "leitor.clientes"))
    rnd = rng(seed, "leitor.t0")
    t_start = time.monotonic()

    def recent_id() -> tuple[str, str] | None:
        hi = int((time.monotonic() - t_start - GET_LAG_S) * tps_trx)
        if hi < 0:
            return None
        hi = min(hi, len(ids) - 1)
        return ids[rnd.randint(max(0, hi - GET_WINDOW_S * tps_trx), hi)]

    def source() -> Iterator[Item]:
        while True:
            got = recent_id() if ids and rnd.random() < fracao_get else None
            if got is not None:
                yield Item("features_get", got[1], f"/v1/features/{got[0]}", None)
                continue
            c = sampler.next()
            k = rnd.randrange(3)
            body = orjson.dumps({"N_ID_CUSTOMER": cliente_id(c), TS: now_iso(), "F_AMOUNT_TRANSACTION": monto(rnd),
                                 "N_MERCHANT_TYPE": mcc_of(c, k), "S_CARD_ACCEPT_EXT_ID_CODE": comercio_of(c, k)})
            yield Item("features_post", cliente_id(c), "/v1/features", body)

    get_tps = round(a.tps * fracao_get) if ids else 0
    meta = {"clients": clients, "distribution": distribution, "seed": seed, "fracao_get": fracao_get if ids else 0,
            "tps_by_operation": {"features_get": get_tps, "features_post": a.tps - get_tps}}
    await _drive("leitor", a, source(), meta, (200,))
    return OK


def _uvloop() -> None:
    try:
        import uvloop

        asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
    except ImportError:
        pass


def run_ingestor(a: LoadArgs, fonte: str, dataset_path: Path, replay: bool) -> int:
    _uvloop()
    return asyncio.run(ingestor(a, fonte, dataset_path, replay))


def run_leitor(a: LoadArgs, clients: int, seed: int, distribution: str, fracao_get: float,
               trx_dataset: Path | None, tps_trx: int) -> int:
    _uvloop()
    return asyncio.run(leitor(a, clients, seed, distribution, fracao_get, trx_dataset, tps_trx))


def _child(target, args, results, key) -> None:
    from sentinela.cli import exit_code_of

    try:
        results[key] = target(*args)
    except BaseException as e:  # noqa: BLE001 - o processo pai decide o código de saída
        print(f"{key}: {e}", file=sys.stderr)
        results[key] = exit_code_of(e)


def carga(ingest: dict[str, LoadArgs], read: LoadArgs | None, data_dir: Path, replay: bool, clients: int,
          seed: int, distribution: str, fracao_get: float) -> int:
    """Um processo por ingestor (TPS > 0) e um para o leitor, cada um com seu event loop e seu GIL."""
    from sentinela.sim.synth import dataset_path

    jobs = {f"ingestor_{f}": (run_ingestor, (a, f, dataset_path(data_dir, f), replay)) for f, a in ingest.items()}
    if read is not None:
        tps_trx = ingest["trx"].tps if "trx" in ingest else 0
        jobs["leitor"] = (run_leitor, (read, clients, seed, distribution, fracao_get, dataset_path(data_dir, "trx"),
                                       tps_trx))
    if not jobs:
        raise UsageError("nada a rodar: todos os TPS são zero")
    ctx = mp.get_context("spawn")
    with ctx.Manager() as m:
        results = m.dict()
        procs = [ctx.Process(target=_child, args=(fn, args, results, name)) for name, (fn, args) in jobs.items()]
        for p in procs:
            p.start()
        for p in procs:
            p.join()
        codes = dict(results)
    for name in jobs:
        if name not in codes:
            raise InvalidRun(f"{name} terminou sem resultado")
    return max(codes.values())
