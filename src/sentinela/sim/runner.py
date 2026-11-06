"""Carga em laço aberto (open loop) sobre asyncio.

Um despachante agenda o item k para start + k/tps e o põe numa fila. `concurrency` workers fazem a chamada e
registram uma linha de log por item. A latência cobre só a chamada. O atraso entre o instante agendado e o
início da chamada (fila) é medido à parte, depois do warmup: é ele que revela a saturação que o TPS e os
percentis escondem. Se os workers não dão conta, a fila enche, o despachante bloqueia e o atraso cresce.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable, Iterator
from dataclasses import dataclass
from typing import Generic, TypeVar

from sentinela.sim.csvlog import CsvLog, ErrorReporter

LATE_THRESHOLD_MS = 100
T = TypeVar("T")

# A chamada devolve (ok, db_us): db_us é o tempo do banco informado pela API, ou None.
Call = Callable[[T], Awaitable[tuple[bool, int | None]]]


@dataclass(frozen=True)
class Result:
    dispatched: int
    source_exhausted: bool
    measured_starts: int
    late_starts: int
    max_start_lag_ms: int


@dataclass
class Step(Generic[T]):
    operation: str
    call: Call
    account_of: Callable[[T], int]


async def run(source: Iterator[T], step: Step[T], tps: int, duration_s: float, warmup_s: float,
              concurrency: int, log: CsvLog, errors: ErrorReporter) -> Result:
    queue: asyncio.Queue = asyncio.Queue(maxsize=max(1, tps))
    measured = late = max_lag = 0
    start = time.perf_counter_ns()
    measure_from = start + int(warmup_s * 1e9)
    end = start + int(duration_s * 1e9)

    async def worker() -> None:
        nonlocal measured, late, max_lag
        while True:
            entry = await queue.get()
            if entry is None:
                return
            item, due = entry
            t0 = time.perf_counter_ns()
            if t0 >= measure_from:
                lag_ms = (t0 - due) // 1_000_000
                measured += 1
                max_lag = max(max_lag, lag_ms)
                if lag_ms > LATE_THRESHOLD_MS:
                    late += 1
            ts_ms = time.time_ns() // 1_000_000
            try:
                ok, db_us = await step.call(item)
            except Exception as e:  # noqa: BLE001 - o erro vira linha de log, não derruba a carga
                ok, db_us = False, None
                errors.report(e)
            log.record(ts_ms, step.operation, step.account_of(item), (time.perf_counter_ns() - t0) // 1_000,
                       db_us, ok)

    workers = [asyncio.create_task(worker()) for _ in range(concurrency)]
    k = 0
    exhausted = False
    while True:
        due = start + (k * 1_000_000_000) // tps
        if due >= end:
            break
        wait = due - time.perf_counter_ns()
        if wait > 0:
            await asyncio.sleep(wait / 1e9)
        item = next(source, None)  # o item é criado no instante agendado (o ingestor carimba ts aqui)
        if item is None:
            exhausted = True
            break
        await queue.put((item, due))
        k += 1
    for _ in workers:
        await queue.put(None)
    await asyncio.gather(*workers)
    return Result(k, exhausted, measured, late, max_lag)
