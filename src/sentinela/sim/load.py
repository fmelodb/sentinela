"""Programas de carga: ingestor (POST /v1/transacoes), leitor (GET .../features) e carga (os dois juntos)."""

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
from datetime import datetime
from pathlib import Path

import aiohttp
import orjson

from sentinela.domain.windows import UTC
from sentinela.errors import INVALID_RUN, OK, InvalidRun, StoreUnavailable
from sentinela.sim import dataset, runner
from sentinela.sim.csvlog import CsvLog, ErrorReporter, update_meta, write_meta
from sentinela.sim.dataset import fmt_amount
from sentinela.sim.samplers import AccountSampler, rng

_DB_DUR = re.compile(r"db;dur=([0-9.]+)")
JSON = {"Content-Type": "application/json"}


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
class Stamped:
    account_id: int
    body: bytes


class HttpError(Exception):
    pass


def default_run() -> str:
    return datetime.now(UTC).strftime("%Y%m%d-%H%M%S")


def default_log(program: str, run: str) -> Path:
    return Path("./logs") / f"{program}-{run}.csv"


def db_us(headers) -> int | None:
    m = _DB_DUR.search(headers.get("Server-Timing", ""))
    return None if m is None else round(float(m.group(1)) * 1000)


def _session(concurrency: int) -> aiohttp.ClientSession:
    return aiohttp.ClientSession(
        connector=aiohttp.TCPConnector(limit=concurrency, keepalive_timeout=60, ttl_dns_cache=None),
        timeout=aiohttp.ClientTimeout(total=10),
        json_serialize=lambda o: orjson.dumps(o).decode(),
    )


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
            "python": platform.python_version(),
            "host": f"{socket.gethostname()} ({os.cpu_count()} CPUs)"}


def _record_lag(log: Path, program: str, r: runner.Result) -> None:
    update_meta(log, measured_starts=r.measured_starts, late_starts=r.late_starts,
                max_start_lag_ms=r.max_start_lag_ms, late_threshold_ms=runner.LATE_THRESHOLD_MS,
                end_epoch_ms=time.time_ns() // 1_000_000)
    print(f"{program}: fila após o warmup: {r.late_starts} de {r.measured_starts} início(s) com atraso > "
          f"{runner.LATE_THRESHOLD_MS} ms; atraso máximo {r.max_start_lag_ms} ms", file=sys.stderr)


async def ingestor(a: LoadArgs, dataset_path: Path) -> int:
    rows = dataset.read_all(dataset_path)
    if a.tps * a.duration > len(rows):
        print(f"aviso: dataset com {len(rows)} transações não cobre tps × duration = {a.tps * a.duration}",
              file=sys.stderr)
    log = a.log or default_log("ingestor", a.run)

    def source() -> Iterator[Stamped]:
        for r in rows:
            ts = datetime.now(UTC).isoformat().replace("+00:00", "Z")
            body = (f'{{"tx_id":{r.tx_id},"account_id":{r.account_id},"ts":"{ts}",'
                    f'"amount":{fmt_amount(r.amount_cents)}}}').encode()
            yield Stamped(r.account_id, body)

    url = f"{a.api_url}/v1/transacoes"
    errors = ErrorReporter()
    async with _session(a.concurrency) as session:
        await check_api(session, a.api_url)

        async def call(s: Stamped) -> tuple[bool, int | None]:
            async with session.post(url, data=s.body, headers=JSON) as resp:
                await resp.read()
                if resp.status not in (200, 201):
                    raise HttpError(f"POST {resp.status}")
                return True, db_us(resp.headers)

        meta = _meta("ingestor", a) | {"dataset": str(dataset_path)}
        with CsvLog(log, "ingestor") as csv:
            write_meta(log, meta)
            r = await runner.run(source(), runner.Step("ingest", call, lambda s: s.account_id), a.tps,
                                 a.duration, a.warmup, a.concurrency, csv, errors)
    _record_lag(log, "ingestor", r)
    errors.summary()
    print(f"ingestor: {r.dispatched} transações despachadas, {errors.total} erro(s) -> {log}", file=sys.stderr)
    if r.source_exhausted:
        print("rodada inválida: o dataset acabou antes de --duration; gere com --count maior", file=sys.stderr)
        return INVALID_RUN
    return OK


async def leitor(a: LoadArgs, accounts: int, seed: int, distribution: str) -> int:
    sampler = AccountSampler(accounts, distribution, rng(seed, "leitor.contas"))
    log = a.log or default_log("leitor", a.run)

    def source() -> Iterator[int]:
        while True:
            yield sampler.next()

    errors = ErrorReporter()
    async with _session(a.concurrency) as session:
        await check_api(session, a.api_url)

        async def call(acc: int) -> tuple[bool, int | None]:
            async with session.get(f"{a.api_url}/v1/contas/{acc}/features") as resp:
                await resp.read()
                if resp.status != 200:
                    raise HttpError(f"GET {resp.status}")
                return True, db_us(resp.headers)

        meta = _meta("leitor", a) | {"accounts": accounts, "distribution": distribution, "seed": seed}
        with CsvLog(log, "leitor") as csv:
            write_meta(log, meta)
            r = await runner.run(source(), runner.Step("read", call, lambda acc: acc), a.tps, a.duration,
                                 a.warmup, a.concurrency, csv, errors)
    _record_lag(log, "leitor", r)
    errors.summary()
    print(f"leitor: {r.dispatched} leituras despachadas, {errors.total} erro(s) -> {log}", file=sys.stderr)
    return OK


def _uvloop() -> None:
    try:
        import uvloop

        asyncio.set_event_loop_policy(uvloop.EventLoopPolicy())
    except ImportError:
        pass


def run_ingestor(a: LoadArgs, dataset_path: Path) -> int:
    _uvloop()
    return asyncio.run(ingestor(a, dataset_path))


def run_leitor(a: LoadArgs, accounts: int, seed: int, distribution: str) -> int:
    _uvloop()
    return asyncio.run(leitor(a, accounts, seed, distribution))


def _child(target, args, results, key) -> None:
    from sentinela.cli import exit_code_of

    try:
        results[key] = target(*args)
    except BaseException as e:  # noqa: BLE001 - o processo pai decide o código de saída
        print(f"{key}: {e}", file=sys.stderr)
        results[key] = exit_code_of(e)


def carga(ingest: LoadArgs, read: LoadArgs, dataset_path: Path, accounts: int, seed: int,
          distribution: str) -> int:
    """Ingestor e leitor em processos separados (cada um com seu event loop e seu GIL), com o mesmo --run."""
    ctx = mp.get_context("spawn")
    with ctx.Manager() as m:
        results = m.dict()
        procs = [ctx.Process(target=_child, args=(run_ingestor, (ingest, dataset_path), results, "ingestor")),
                 ctx.Process(target=_child, args=(run_leitor, (read, accounts, seed, distribution), results,
                                                  "leitor"))]
        for p in procs:
            p.start()
        for p in procs:
            p.join()
        codes = dict(results)
    for name in ("ingestor", "leitor"):
        if name not in codes:
            raise InvalidRun(f"{name} terminou sem resultado")
    return max(codes.values())
