"""Agregados decomponíveis (cnt, sum, min, max em centavos) e o vetor de 95 features."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import ROUND_HALF_EVEN, Decimal

from sentinela.domain.windows import SIZE, SUFFIXES

METRICS = ("cnt", "sum", "min", "max", "avg")
NAMES: tuple[str, ...] = tuple(f"{m}_{s}" for s in SUFFIXES for m in METRICS)
_AVG_SCALE = Decimal("0.0001")


@dataclass(frozen=True, slots=True)
class Transaction:
    tx_id: int
    account_id: int
    ts: datetime  # com fuso; precisão de microssegundo
    amount_cents: int

    def __post_init__(self) -> None:
        if self.account_id <= 0:
            raise ValueError(f"account_id precisa ser positivo: {self.account_id}")
        if self.amount_cents <= 0:
            raise ValueError(f"amount precisa ser positivo: {self.amount_cents}")
        if self.ts.tzinfo is None:
            raise ValueError(f"ts sem fuso: {self.ts}")


@dataclass(frozen=True, slots=True)
class WindowAgg:
    """min/max são None quando cnt = 0."""

    cnt: int = 0
    sum_cents: int = 0
    min_cents: int | None = None
    max_cents: int | None = None

    def __post_init__(self) -> None:
        if self.cnt < 0:
            raise ValueError(f"cnt negativo: {self.cnt}")
        if self.cnt == 0 and (self.sum_cents != 0 or self.min_cents is not None or self.max_cents is not None):
            raise ValueError(f"agregado vazio com valores: {self}")
        if self.cnt > 0 and (self.min_cents is None or self.max_cents is None):
            raise ValueError("agregado com cnt > 0 sem min/max")

    @staticmethod
    def of(amount_cents: int) -> WindowAgg:
        return WindowAgg(1, amount_cents, amount_cents, amount_cents)

    def plus(self, o: WindowAgg) -> WindowAgg:
        if o.cnt == 0:
            return self
        if self.cnt == 0:
            return o
        return WindowAgg(self.cnt + o.cnt, self.sum_cents + o.sum_cents,
                         min(self.min_cents, o.min_cents), max(self.max_cents, o.max_cents))  # type: ignore[type-var]


EMPTY = WindowAgg()


def _money(cents: int | None) -> Decimal | None:
    return None if cents is None else Decimal(cents).scaleb(-2)


@dataclass(frozen=True, slots=True)
class FeatureVector:
    """Os 19 agregados de uma leitura; values() expande para os 95 valores na ordem da spec."""

    aggs: tuple[WindowAgg, ...]

    def __post_init__(self) -> None:
        if len(self.aggs) != SIZE:
            raise ValueError(f"vetor precisa de {SIZE} janelas: {len(self.aggs)}")

    @staticmethod
    def empty() -> FeatureVector:
        return FeatureVector((EMPTY,) * SIZE)

    def values(self) -> list[int | Decimal | None]:
        """95 valores; min, max e avg são None quando cnt = 0. avg com 4 casas, HALF_EVEN."""
        out: list[int | Decimal | None] = []
        for a in self.aggs:
            out.append(a.cnt)
            out.append(_money(a.sum_cents))
            out.append(_money(a.min_cents))
            out.append(_money(a.max_cents))
            out.append(None if a.cnt == 0 else
                       (Decimal(a.sum_cents) / Decimal(a.cnt * 100)).quantize(_AVG_SCALE, ROUND_HALF_EVEN))
        return out

    def to_json(self) -> dict[str, int | float | None]:
        """Os 95 valores por nome, em tipos JSON (dinheiro e média como número)."""
        return {name: (v if v is None or isinstance(v, int) else float(v))
                for name, v in zip(NAMES, self.values(), strict=True)}
