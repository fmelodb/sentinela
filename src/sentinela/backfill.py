"""Histórico do backfill: transações de 00:00 de D-3 até agora (vão para TX) e agregados diários sintéticos
para os dias mais antigos (vão direto para DAILY_AGG). Destrutivo: esvazia TX e DAILY_AGG antes."""

from __future__ import annotations

import sys
import time
from collections.abc import Iterator
from datetime import date, datetime, timedelta, tzinfo

import numpy as np
import oracledb

from sentinela.db import plan
from sentinela.db.convert import naive_utc
from sentinela.db.maintenance import Maintenance
from sentinela.domain.windows import as_utc, civil_day, day_start
from sentinela.errors import InvalidRun
from sentinela.sim.samplers import np_accounts, np_amounts, np_rng

TX_ID_BASE = 1_000_000_000_000_000
RECENT_DAYS = 3
MAX_DAYS = 92


class BackfillGenerator:
    def __init__(self, accounts: int, distribution: str, tx_per_day: int, seed: int, zone: tzinfo, now: datetime):
        self.accounts = accounts
        self.distribution = distribution
        self.tx_per_day = tx_per_day
        self.seed = seed
        self.zone = zone
        self.now = as_utc(now)
        self.today = civil_day(self.now, zone)

    def recent_transactions(self) -> Iterator[tuple[int, int, datetime, int]]:
        """(tx_id, account_id, ts naive UTC, centavos), de 00:00 de D-3 até now, proporcional ao tempo decorrido."""
        g_acc = np_rng(self.seed, "backfill.contas")
        g_val = np_rng(self.seed, "backfill.valores")
        g_ts = np_rng(self.seed, "backfill.ts")
        next_id = TX_ID_BASE + 1
        for i in range(RECENT_DAYS, -1, -1):
            d = self.today - timedelta(days=i)
            start = day_start(d, self.zone)
            next_day = day_start(d + timedelta(days=1), self.zone)
            end = min(next_day, self.now)
            span_us = (end - start) // timedelta(microseconds=1)
            day_us = (next_day - start) // timedelta(microseconds=1)
            if span_us <= 0:
                continue
            n = round(self.accounts * self.tx_per_day * span_us / day_us)
            accs = np_accounts(g_acc, n, self.accounts, self.distribution)
            vals = np_amounts(g_val, n)
            offsets = g_ts.integers(0, span_us, size=n, dtype=np.int64)
            base = naive_utc(start)
            for a, v, off in zip(accs.tolist(), vals.tolist(), offsets.tolist(), strict=True):
                yield next_id, a, base + timedelta(microseconds=off), v
                next_id += 1

    def synthetic_day(self, d: date) -> Iterator[tuple[int, int, int, int, int]]:
        """(account_id, cnt, soma, mínimo, máximo) em centavos, para as contas com transação no dia."""
        g = np_rng(self.seed, f"backfill.dia.{d.isoformat()}")
        total = self.accounts * self.tx_per_day
        accs = np_accounts(g, total, self.accounts, self.distribution)
        vals = np_amounts(g, total)
        size = self.accounts + 1
        cnt = np.bincount(accs, minlength=size)
        sums = np.zeros(size, dtype=np.int64)
        np.add.at(sums, accs, vals)
        mins = np.full(size, np.iinfo(np.int64).max, dtype=np.int64)
        np.minimum.at(mins, accs, vals)
        maxs = np.zeros(size, dtype=np.int64)
        np.maximum.at(maxs, accs, vals)
        idx = np.nonzero(cnt)[0]
        yield from zip(idx.tolist(), cnt[idx].tolist(), sums[idx].tolist(), mins[idx].tolist(), maxs[idx].tolist(),
                       strict=True)


def run(conn: oracledb.Connection, zone: tzinfo, accounts: int, days: int, seed: int, distribution: str,
        tx_per_day: int, now: datetime) -> None:
    t0 = time.perf_counter()

    def step(msg: str) -> None:
        print(f"backfill [{time.perf_counter() - t0:6.1f}s] {msg}", file=sys.stderr)

    gen = BackfillGenerator(accounts, distribution, tx_per_day, seed, zone, now)
    today = gen.today
    m = Maintenance(conn, zone)

    m.reset()
    step("TX e DAILY_AGG esvaziadas")
    n = m.bulk_insert_tx(gen.recent_transactions())
    step(f"{n} transações em TX desde {today - timedelta(days=RECENT_DAYS)}")
    for i in range(RECENT_DAYS, 0, -1):
        d = today - timedelta(days=i)
        step(f"DAILY_AGG de {d} a partir de TX: {m.close_day(d)} contas")
    synthetic = 0
    first = today - timedelta(days=days)
    for k in range(days - RECENT_DAYS):
        d = first + timedelta(days=k)
        synthetic += m.bulk_insert_daily(d, gen.synthetic_day(d))
        if k % 10 == 9:
            step(f"DAILY_AGG sintético até {d}: {synthetic} linhas")
    step(f"DAILY_AGG sintético: {synthetic} linhas")
    m.gather_stats()
    step("estatísticas coletadas")

    # Confere o plano real da leitura sobre o volume da rodada; plano errado invalida a rodada.
    m.read(1, gen.now)
    lines = m.read_plan()
    problems = plan.problems(lines)
    if problems:
        print("\n".join(lines), file=sys.stderr)
        raise InvalidRun("plano de leitura inválido: " + "; ".join(problems))
    step("plano de leitura ok (índice coberto, poda de partições, sem acesso à tabela)")
