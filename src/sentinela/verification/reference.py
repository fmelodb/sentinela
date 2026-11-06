"""Cálculo direto das 19 janelas sobre a lista de transações, pela seção 3 da spec funcional, sem usar
windows.limits nem o SQL. É o voto independente: se o Oracle errar, a referência discorda."""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timedelta, tzinfo

from sentinela.domain.agg import EMPTY, FeatureVector, Transaction, WindowAgg
from sentinela.domain.windows import DAYS, ROLLING, day_start


def compute(txs: list[Transaction], now: datetime, zone: tzinfo) -> FeatureVector:
    today = now.astimezone(zone).date()
    aggs = []
    for w in ROLLING:
        lo = now - w.span
        aggs.append(_sum(txs, lambda ts, lo=lo: lo < ts <= now))
    for w in DAYS:
        lo = day_start(today - timedelta(days=w.n - 1), zone)
        aggs.append(_sum(txs, lambda ts, lo=lo: lo <= ts <= now))
    return FeatureVector(tuple(aggs))


def _sum(txs: list[Transaction], inside: Callable[[datetime], bool]) -> WindowAgg:
    acc = EMPTY
    for t in txs:
        if inside(t.ts):
            acc = acc.plus(WindowAgg.of(t.amount_cents))
    return acc
