"""Amostradores determinísticos por seed: contas (uniform ou skewed) e valores lognormais.

skewed: com probabilidade 0,5 sorteia entre as contas quentes [1, max(1, N/100)]; senão, uniforme em [1, N].
Cerca de 50% das transações caem em 1% das contas. Valores: lognormal com mediana R$ 150 (μ = ln 150,
σ = 1,2), em centavos, limitados a [R$ 1; R$ 50.000].

Há duas versões com a mesma distribuição: escalar (random.Random), para o simulador e a equivalência, e
vetorizada (numpy), para o backfill e o gerador, que sorteiam milhões de valores.
"""

from __future__ import annotations

import hashlib
import math
import random

import numpy as np

from sentinela.errors import UsageError

MIN_CENTS = 100
MAX_CENTS = 5_000_000
MU = math.log(150.0)
SIGMA = 1.2
DISTRIBUTIONS = ("skewed", "uniform")


def derive(seed: int, purpose: str) -> int:
    """Uma semente independente por propósito, derivada do --seed."""
    return int.from_bytes(hashlib.sha256(f"{seed}:{purpose}".encode()).digest()[:8], "big")


def rng(seed: int, purpose: str) -> random.Random:
    return random.Random(derive(seed, purpose))


def np_rng(seed: int, purpose: str) -> np.random.Generator:
    return np.random.default_rng(derive(seed, purpose))


def check_distribution(d: str) -> str:
    if d not in DISTRIBUTIONS:
        raise UsageError(f"--distribution precisa ser uniform ou skewed: {d}")
    return d


def hot_accounts(accounts: int) -> int:
    return max(1, accounts // 100)


class AccountSampler:
    def __init__(self, accounts: int, distribution: str, rnd: random.Random):
        if accounts <= 0:
            raise ValueError(f"universo de contas vazio: {accounts}")
        self.accounts = accounts
        self.hot = hot_accounts(accounts)
        self.skewed = check_distribution(distribution) == "skewed"
        self.rnd = rnd

    def next(self) -> int:
        if self.skewed and self.rnd.random() < 0.5:
            return 1 + self.rnd.randrange(self.hot)
        return 1 + self.rnd.randrange(self.accounts)


class AmountSampler:
    def __init__(self, rnd: random.Random):
        self.rnd = rnd

    def next_cents(self) -> int:
        reais = math.exp(MU + SIGMA * self.rnd.gauss(0.0, 1.0))
        return min(MAX_CENTS, max(MIN_CENTS, round(reais * 100)))


def np_accounts(g: np.random.Generator, n: int, accounts: int, distribution: str) -> np.ndarray:
    a = g.integers(1, accounts + 1, size=n, dtype=np.int64)
    if check_distribution(distribution) == "skewed":
        mask = g.random(n) < 0.5
        a[mask] = g.integers(1, hot_accounts(accounts) + 1, size=int(mask.sum()), dtype=np.int64)
    return a


def np_amounts(g: np.random.Generator, n: int) -> np.ndarray:
    reais = np.exp(MU + SIGMA * g.standard_normal(n))
    return np.clip(np.rint(reais * 100), MIN_CENTS, MAX_CENTS).astype(np.int64)
