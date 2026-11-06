"""Conversões entre o domínio e as colunas: TIMESTAMP em UTC sem fuso, DATE como dia civil, NUMBER(14,2)."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from sentinela.domain.windows import UTC


def naive_utc(t: datetime) -> datetime:
    if t.tzinfo is None:
        raise ValueError(f"instante sem fuso: {t}")
    return t.astimezone(UTC).replace(tzinfo=None)


def aware_utc(t: datetime) -> datetime:
    return t.replace(tzinfo=UTC)


def cents(v: Decimal | int) -> int:
    d = Decimal(v).scaleb(2)
    if d != d.to_integral_value():
        raise ValueError(f"valor com mais de 2 casas: {v}")
    return int(d)


def amount(c: int) -> Decimal:
    return Decimal(c).scaleb(-2)
