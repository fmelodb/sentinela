"""Amostradores determinísticos por seed: clientes (uniform ou skewed) e montos lognormais em pesos chilenos.

skewed: com probabilidade 0,5 sorteia entre os clientes quentes [1, max(1, N/100)]; senão, uniforme em [1, N].
Cerca de 50% dos eventos caem em 1% dos clientes. Montos: lognormal com mediana CLP 25.000 (σ = 1,2), inteiros,
limitados a [CLP 500; CLP 5.000.000].

Há duas versões com a mesma distribuição: escalar (random.Random), para o leitor, e vetorizada (numpy), para o
backfill e o gerador, que sorteiam milhões de valores.
"""

from __future__ import annotations

import hashlib
import math
import random

import numpy as np

from sentinela.errors import UsageError

MIN_MONTO = 500
MAX_MONTO = 5_000_000
MU = math.log(25_000.0)
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


def hot_clients(clients: int) -> int:
    return max(1, clients // 100)


class ClientSampler:
    def __init__(self, clients: int, distribution: str, rnd: random.Random):
        if clients <= 0:
            raise ValueError(f"universo de clientes vazio: {clients}")
        self.clients = clients
        self.hot = hot_clients(clients)
        self.skewed = check_distribution(distribution) == "skewed"
        self.rnd = rnd

    def next(self) -> int:
        if self.skewed and self.rnd.random() < 0.5:
            return 1 + self.rnd.randrange(self.hot)
        return 1 + self.rnd.randrange(self.clients)


def monto(rnd: random.Random) -> int:
    return min(MAX_MONTO, max(MIN_MONTO, round(math.exp(MU + SIGMA * rnd.gauss(0.0, 1.0)))))


def np_clients(g: np.random.Generator, n: int, clients: int, distribution: str) -> np.ndarray:
    a = g.integers(1, clients + 1, size=n, dtype=np.int64)
    if check_distribution(distribution) == "skewed":
        mask = g.random(n) < 0.5
        a[mask] = g.integers(1, hot_clients(clients) + 1, size=int(mask.sum()), dtype=np.int64)
    return a


def np_montos(g: np.random.Generator, n: int) -> np.ndarray:
    v = np.exp(MU + SIGMA * g.standard_normal(n))
    return np.clip(np.rint(v), MIN_MONTO, MAX_MONTO).astype(np.int64)
