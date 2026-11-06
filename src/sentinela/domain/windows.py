"""As 19 janelas da seção 3 da spec funcional e a regra única dos limites.

Janelas deslizantes: now − span < ts ≤ now, ao microssegundo. Janelas de dias: n − 1 dias civis fechados
em FS_ZONE mais o dia corrente até now. Todos os instantes são datetimes com fuso UTC.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone, tzinfo

UTC = timezone.utc


@dataclass(frozen=True, slots=True)
class RollingWindow:
    suffix: str
    span: timedelta


@dataclass(frozen=True, slots=True)
class DayWindow:
    suffix: str
    n: int


ROLLING: tuple[RollingWindow, ...] = (
    RollingWindow("1m", timedelta(minutes=1)),
    RollingWindow("5m", timedelta(minutes=5)),
    RollingWindow("10m", timedelta(minutes=10)),
    RollingWindow("15m", timedelta(minutes=15)),
    RollingWindow("30m", timedelta(minutes=30)),
    RollingWindow("1h", timedelta(hours=1)),
    RollingWindow("2h", timedelta(hours=2)),
    RollingWindow("3h", timedelta(hours=3)),
    RollingWindow("5h", timedelta(hours=5)),
    RollingWindow("6h", timedelta(hours=6)),
    RollingWindow("9h", timedelta(hours=9)),
    RollingWindow("12h", timedelta(hours=12)),
    RollingWindow("24h", timedelta(hours=24)),
    RollingWindow("48h", timedelta(hours=48)),
    RollingWindow("72h", timedelta(hours=72)),
)

DAYS: tuple[DayWindow, ...] = (
    DayWindow("7d", 7),
    DayWindow("31d", 31),
    DayWindow("62d", 62),
    DayWindow("92d", 92),
)

SUFFIXES: tuple[str, ...] = tuple(w.suffix for w in ROLLING) + tuple(w.suffix for w in DAYS)
SIZE = len(SUFFIXES)
FIRST_DAY_WINDOW = len(ROLLING)


def as_utc(t: datetime) -> datetime:
    if t.tzinfo is None:
        raise ValueError(f"instante sem fuso: {t}")
    return t.astimezone(UTC)


def civil_day(t: datetime, zone: tzinfo) -> date:
    return as_utc(t).astimezone(zone).date()


def day_start(d: date, zone: tzinfo) -> datetime:
    """00:00 do dia civil d em zone, como instante UTC."""
    return datetime.combine(d, time(), tzinfo=zone).astimezone(UTC)


@dataclass(frozen=True, slots=True)
class Limits:
    """Limites de uma leitura em now. Janela deslizante i: rolling_lo[i] < ts ≤ now. Dia corrente:
    today_start ≤ ts ≤ now. Janela de dias j: dias fechados de closed_lo[j] a today − 1, mais o dia corrente."""

    now: datetime
    rolling_lo: tuple[datetime, ...]
    today: date
    today_start: datetime
    closed_lo: tuple[date, ...]

    def in_rolling(self, i: int, ts: datetime) -> bool:
        return self.rolling_lo[i] < ts <= self.now

    def in_today(self, ts: datetime) -> bool:
        return self.today_start <= ts <= self.now

    def in_closed(self, j: int, d: date) -> bool:
        return self.closed_lo[j] <= d < self.today

    @property
    def oldest_rolling(self) -> datetime:
        """Limite inferior da maior janela deslizante (72h): a faixa de transações que a leitura busca."""
        return self.rolling_lo[-1]

    @property
    def oldest_closed(self) -> date:
        """Primeiro dia fechado da maior janela de dias (92d)."""
        return self.closed_lo[-1]


def limits(now: datetime, zone: tzinfo) -> Limits:
    n = as_utc(now)
    today = civil_day(n, zone)
    return Limits(
        now=n,
        rolling_lo=tuple(n - w.span for w in ROLLING),
        today=today,
        today_start=day_start(today, zone),
        closed_lo=tuple(today - timedelta(days=w.n - 1) for w in DAYS),
    )
