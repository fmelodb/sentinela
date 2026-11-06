"""Leitura dos logs e estatísticas: descarta o warmup, calcula TPS, erros, fila e latências HTTP e de banco."""

from __future__ import annotations

import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import TextIO

from sentinela.sim.csvlog import HEADER, meta_path, read_meta

MAX_TPS_DEVIATION = 0.05
MAX_ERROR_RATE = 0.001
MAX_LATE_START_RATE = 0.01


@dataclass(frozen=True, slots=True)
class Row:
    ts_ms: int
    program: str
    operation: str
    account_id: int
    latency_us: int
    db_us: int | None
    ok: bool


@dataclass(frozen=True)
class LogFile:
    csv: Path
    meta: dict
    rows: list[Row]

    @property
    def run(self) -> str:
        return str(self.meta.get("run", self.csv.name))

    @property
    def start_ms(self) -> int:
        return int(self.meta["start_epoch_ms"])

    @property
    def warmup_s(self) -> int:
        return int(self.meta.get("warmup", 0))

    @property
    def target_tps(self) -> int:
        return int(self.meta["tps"])


@dataclass(frozen=True)
class Latency:
    n: int
    min_us: int
    avg_us: float
    p95_us: int
    p99_us: int
    max_us: int


@dataclass(frozen=True)
class GroupStats:
    run: str
    start_ms: int
    program: str
    operation: str
    ops: int
    errors: int
    error_rate: float
    duration_s: float
    target_tps: int
    achieved_tps: float
    deviation: float
    http: Latency
    db: Latency | None
    tps_ok: bool
    error_ok: bool
    max_start_lag_ms: int  # -1 quando o log não traz a medição de fila
    lag_ok: bool


@dataclass(frozen=True)
class SeriesRow:
    run: str
    program: str
    operation: str
    second: int
    ops: int
    warmup: bool


def nearest_rank(sorted_values: list[int], permille: int) -> int:
    """Percentil nearest-rank, sem interpolação: posição ceil(q × n), base 1. permille: 950 = p95."""
    n = len(sorted_values)
    if n == 0:
        raise ValueError("amostra vazia")
    rank = (permille * n + 999) // 1000
    return sorted_values[max(1, rank) - 1]


def latency(values: list[int]) -> Latency:
    v = sorted(values)
    return Latency(len(v), v[0], sum(v) / len(v), nearest_rank(v, 950), nearest_rank(v, 990), v[-1])


def load(directory: Path, warn: TextIO = sys.stderr) -> list[LogFile]:
    out = []
    for csv in sorted(directory.glob("*.csv")):
        if not meta_path(csv).exists():
            warn.write(f"aviso: {csv.name} sem .meta.json; ignorado\n")
            continue
        out.append(LogFile(csv, read_meta(csv), _rows(csv, warn)))
    return out


def _rows(csv: Path, warn: TextIO) -> list[Row]:
    rows, bad = [], 0
    with csv.open(encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line == HEADER:
                continue
            p = line.split(",")
            try:
                if len(p) != 7:
                    raise ValueError
                rows.append(Row(int(p[0]), sys.intern(p[1]), sys.intern(p[2]), int(p[3]), int(p[4]),
                                int(p[5]) if p[5] else None, p[6] == "ok"))
            except ValueError:
                bad += 1
    if bad:
        warn.write(f"aviso: {csv.name}: {bad} linha(s) malformada(s) ignorada(s)\n")
    return rows


def build(files: list[LogFile], warn: TextIO = sys.stderr) -> list[GroupStats]:
    out = []
    for f in files:
        cutoff = f.start_ms + f.warmup_s * 1000
        by_op: dict[str, list[Row]] = defaultdict(list)
        for r in f.rows:
            if r.ts_ms >= cutoff:
                by_op[r.operation].append(r)
        if not by_op:
            warn.write(f"aviso: run {f.run} ({f.csv.name}) sem operações após o warmup\n")
            continue
        for op in sorted(by_op):
            g = _stats(f, op, by_op[op])
            if g is None:
                warn.write(f"aviso: run {f.run} operação {op} sem operações ok após o warmup\n")
            else:
                out.append(g)
    out.sort(key=lambda g: (g.run, g.program, g.operation))
    return out


def _stats(f: LogFile, op: str, rows: list[Row]) -> GroupStats | None:
    ok = [r for r in rows if r.ok]
    if not ok:
        return None
    errors = len(rows) - len(ok)
    first = min(r.ts_ms for r in rows)
    last = max(r.ts_ms for r in rows)
    duration_s = max(1, last - first) / 1000.0
    achieved = len(ok) / duration_s
    target = f.target_tps
    deviation = abs(achieved - target) / target
    error_rate = errors / len(rows)
    dbs = [r.db_us for r in ok if r.db_us is not None]
    return GroupStats(
        run=f.run, start_ms=f.start_ms, program=rows[0].program, operation=op, ops=len(ok), errors=errors,
        error_rate=error_rate, duration_s=duration_s, target_tps=target, achieved_tps=achieved,
        deviation=deviation, http=latency([r.latency_us for r in ok]), db=latency(dbs) if dbs else None,
        tps_ok=deviation < MAX_TPS_DEVIATION, error_ok=error_rate < MAX_ERROR_RATE,
        max_start_lag_ms=int(f.meta.get("max_start_lag_ms", -1)), lag_ok=_lag_ok(f))


def _lag_ok(f: LogFile) -> bool:
    measured = int(f.meta.get("measured_starts", -1))
    late = int(f.meta.get("late_starts", -1))
    if measured < 0 or late < 0:
        return True  # log sem medição de fila
    return late <= MAX_LATE_START_RATE * measured


def series(files: list[LogFile]) -> list[SeriesRow]:
    out = []
    for f in files:
        by_op: dict[str, list[Row]] = defaultdict(list)
        for r in f.rows:
            by_op[r.operation].append(r)
        for op in sorted(by_op):
            rows = by_op[op]
            counts: dict[int, int] = defaultdict(int)
            max_s = -1
            for r in rows:
                s = (r.ts_ms - f.start_ms) // 1000
                max_s = max(max_s, s)
                if r.ok and s >= 0:
                    counts[s] += 1
            out.extend(SeriesRow(f.run, rows[0].program, op, s, counts[s], s < f.warmup_s) for s in range(max_s + 1))
    return out
