"""Sequência do cenário C: 93 dias de transações em quatro classes de conta (conta mod 4)."""

from __future__ import annotations

import random
from datetime import datetime, timedelta, tzinfo

from sentinela.domain.agg import Transaction
from sentinela.domain.windows import UTC, as_utc, day_start, limits
from sentinela.sim.samplers import AmountSampler, rng

NORMAL = 0
SILENT_LAST_HOUR = 1
TODAY_ONLY = 2
BOUNDARIES = 3
HISTORY_DAYS = 93
US = timedelta(microseconds=1)


def account_class(account_id: int) -> int:
    return account_id % 4


def boundaries(now: datetime, zone: tzinfo) -> list[datetime]:
    """Os dois lados de cada limite da seção 3: now − span (fora) e 1 µs depois (dentro); now (dentro) e 1 µs
    depois (futuro, fora); 00:00 local de hoje e de cada primeiro dia fechado, e 1 µs antes; 00:00 UTC."""
    lim = limits(now, zone)
    s: dict[datetime, None] = {}
    for lo in lim.rolling_lo:
        s[lo] = None
        s[lo + US] = None
    s[lim.now] = None
    s[lim.now + US] = None
    midnights = [lim.today_start] + [day_start(d, zone) for d in lim.closed_lo]
    midnights.append(lim.now.replace(hour=0, minute=0, second=0, microsecond=0))
    for t in midnights:
        s[t] = None
        s[t - US] = None
    return list(s)


def _uniform(rnd: random.Random, n: int, lo: datetime, hi: datetime) -> list[datetime]:
    span = (hi - lo) // US + 1  # inclusivo nos dois lados
    return [] if span <= 0 else [lo + rnd.randrange(span) * US for _ in range(n)]


def generate(accounts: int, seed: int, now: datetime, zone: tzinfo) -> list[Transaction]:
    now = as_utc(now)
    rnd = rng(seed, "equivalencia")
    amounts = AmountSampler(rnd)
    start = now - timedelta(days=HISTORY_DAYS)
    hour_ago = now - timedelta(hours=1)
    today_start = day_start(now.astimezone(zone).date(), zone)
    edges = boundaries(now, zone)
    out = []
    next_id = 1
    for acc in range(1, accounts + 1):
        cls = account_class(acc)
        if cls == NORMAL:
            times = _uniform(rnd, rnd.randrange(301), start, now)
        elif cls == SILENT_LAST_HOUR:
            times = _uniform(rnd, rnd.randrange(301), start, hour_ago)
        elif cls == TODAY_ONLY:
            times = _uniform(rnd, 1 + rnd.randrange(20), today_start, now)
        else:
            times = edges
        for ts in times:
            out.append(Transaction(next_id, acc, ts.astimezone(UTC), amounts.next_cents()))
            next_id += 1
    out.sort(key=lambda t: (t.ts, t.tx_id))
    return out
