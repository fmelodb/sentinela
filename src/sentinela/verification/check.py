"""Cenário C e critérios funcionais, sem carga, de ponta a ponta: as transações entram pela ingest API, os
vetores saem pela query API, e o resultado é comparado com o modelo de referência no mesmo instante lógico.
O Job diário e a contagem de TX usam o banco direto. Destrutivo: exige banco vazio ou reset."""

from __future__ import annotations

import asyncio
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, tzinfo
from typing import TextIO

import aiohttp
import orjson

from sentinela.db.maintenance import Maintenance
from sentinela.domain.agg import NAMES, Transaction
from sentinela.domain.windows import FIRST_DAY_WINDOW, SIZE, SUFFIXES
from sentinela.errors import StoreUnavailable, UsageError
from sentinela.sim.dataset import fmt_amount
from sentinela.verification import data, reference

MAX_DETAILS = 20
LAST_MINUTE_WINDOW = 5  # 1h
DAY_SLICES = 92
CONCURRENCY = 32


@dataclass
class CheckResult:
    name: str
    details: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.details

    def add(self, s: str) -> None:
        if len(self.details) < MAX_DETAILS:
            self.details.append(s)
        elif len(self.details) == MAX_DETAILS:
            self.details.append("…")


def _iso(t: datetime) -> str:
    return t.isoformat().replace("+00:00", "Z")


def diff(account: int, la: str, a: dict, lb: str, b: dict) -> list[str]:
    return [f"conta {account} {n}: {la}={a.get(n)} {lb}={b.get(n)}" for n in NAMES if a.get(n) != b.get(n)]


class Api:
    def __init__(self, session: aiohttp.ClientSession, url: str):
        self.session = session
        self.url = url
        self.sem = asyncio.Semaphore(CONCURRENCY)

    async def ingest(self, t: Transaction) -> int:
        body = (f'{{"tx_id":{t.tx_id},"account_id":{t.account_id},"ts":"{_iso(t.ts)}",'
                f'"amount":{fmt_amount(t.amount_cents)}}}').encode()
        async with self.sem, self.session.post(f"{self.url}/v1/transacoes", data=body,
                                               headers={"Content-Type": "application/json"}) as r:
            await r.read()
            return r.status

    async def features(self, account: int, now: datetime) -> dict:
        async with self.sem, self.session.get(f"{self.url}/v1/contas/{account}/features",
                                              params={"now": _iso(now)}) as r:
            body = await r.read()
            if r.status != 200:
                raise StoreUnavailable(f"GET features da conta {account}: {r.status} {body[:200]!r}")
            return orjson.loads(body)["features"]


async def _ingest_all(api: Api, txs: list[Transaction], progress: TextIO) -> dict[int, int]:
    statuses: dict[int, int] = {}
    chunk = 5_000
    for i in range(0, len(txs), chunk):
        part = txs[i:i + chunk]
        for t, s in zip(part, await asyncio.gather(*(api.ingest(t) for t in part)), strict=True):
            statuses[t.tx_id] = s
        if (i + chunk) % 20_000 == 0:
            print(f"equivalencia: {min(i + chunk, len(txs))}/{len(txs)}", file=progress)
    return statuses


async def _read_all(api: Api, accounts: int, now: datetime) -> list[dict]:
    return list(await asyncio.gather(*(api.features(a, now) for a in range(1, accounts + 1))))


async def run(m: Maintenance, api_url: str, zone: tzinfo, accounts: int, seed: int, now: datetime, reset: bool,
              progress: TextIO = sys.stderr) -> list[CheckResult]:
    if not reset and not m.is_empty():
        raise UsageError("o banco não está vazio; rode com --reset (apaga TX e DAILY_AGG)")
    m.reset()

    txs = data.generate(accounts, seed, now, zone)
    by_account: dict[int, list[Transaction]] = {}
    for t in txs:
        by_account.setdefault(t.account_id, []).append(t)

    shape = CheckResult("read devolve 95 valores na ordem da seção 3")
    equal = CheckResult(f"API (Oracle) = modelo de referência nas 95 posições ({accounts} contas)")
    silent = CheckResult("sem transação na última hora: cnt = 0 nos minutos e em 1h")
    today_chk = CheckResult("janelas de dias incluem o dia corrente até now")
    job = CheckResult("Job diário = recálculo direto sobre TX")
    dup = CheckResult("reingestão: a API devolve 200 duplicada para todas as transações")
    idem = CheckResult("reaplicar a sequência não altera os contadores")

    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30),
                                     connector=aiohttp.TCPConnector(limit=CONCURRENCY)) as session:
        api = Api(session, api_url)
        try:
            async with session.get(f"{api_url}/health") as r:
                if r.status != 200:
                    raise StoreUnavailable(f"API em {api_url}: /health devolveu {r.status}")
        except aiohttp.ClientError as e:
            raise StoreUnavailable(f"API em {api_url}: {e}") from e

        print(f"equivalencia: aplicando {len(txs)} transações pela ingest API", file=progress)
        first = await _ingest_all(api, txs, progress)
        not_created = [i for i, s in first.items() if s != 201]
        if not_created:
            equal.add(f"{len(not_created)} transação(ões) sem 201 na primeira ingestão, ex.: tx_id {not_created[0]}")

        today = now.astimezone(zone).date()
        for k in range(DAY_SLICES, 0, -1):
            d = today - timedelta(days=k)
            m.close_day(d)
            if (n := m.check_day(d)) != 0:
                job.add(f"dia {d}: {n} linha(s) divergentes")
        print(f"equivalencia: dias fechados de {today - timedelta(days=DAY_SLICES)} a {today - timedelta(days=1)}",
              file=progress)

        vectors = await _read_all(api, accounts, now)
        if len(NAMES) != 95 or NAMES[0] != "cnt_1m" or NAMES[-1] != "avg_92d":
            shape.add(f"nomes fora da ordem da spec: {NAMES}")
        for acc, got in enumerate(vectors, 1):
            if list(got) != list(NAMES):
                shape.add(f"conta {acc}: chaves fora da ordem ou sem 95 posições")
            ref = reference.compute(by_account.get(acc, []), now, zone).to_json()
            for s in diff(acc, "api", got, "referencia", ref):
                equal.add(s)
            cls = data.account_class(acc)
            n_tx = len(by_account.get(acc, []))
            for w in range(SIZE):
                name = f"cnt_{SUFFIXES[w]}"
                if cls == data.SILENT_LAST_HOUR and w <= LAST_MINUTE_WINDOW and got[name] != 0:
                    silent.add(f"conta {acc} {name}: {got[name]}")
                if cls == data.TODAY_ONLY and w >= FIRST_DAY_WINDOW and got[name] != n_tx:
                    today_chk.add(f"conta {acc} {name}: esperado={n_tx} api={got[name]}")

        before = m.count_tx()
        print("equivalencia: reaplicando a sequência pela ingest API", file=progress)
        second = await _ingest_all(api, txs, progress)
        not_dup = [i for i, s in second.items() if s != 200]
        if not_dup:
            dup.add(f"{len(not_dup)} transação(ões) sem 200 na reingestão, ex.: tx_id {not_dup[0]}")
        after = m.count_tx()
        if before != after:
            idem.add(f"COUNT(*) de TX mudou de {before} para {after}")
        again = await _read_all(api, accounts, now)
        for acc, (a, b) in enumerate(zip(vectors, again, strict=True), 1):
            for s in diff(acc, "antes", a, "depois", b):
                idem.add(s)

    return [shape, equal, silent, today_chk, job, dup, idem]
