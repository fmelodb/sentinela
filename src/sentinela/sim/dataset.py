"""Dataset NDJSON de transações sem instante: {"tx_id":1,"account_id":4711,"amount":123.45} por linha.
O ingestor carimba ts = agora ao despachar cada transação."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

import orjson

from sentinela.errors import UsageError
from sentinela.sim.samplers import np_accounts, np_amounts, np_rng


@dataclass(frozen=True, slots=True)
class Row:
    tx_id: int
    account_id: int
    amount_cents: int


def fmt_amount(c: int) -> str:
    return f"{c // 100}.{c % 100:02d}"


def generate(path: Path, accounts: int, count: int, seed: int, distribution: str) -> None:
    g_acc = np_rng(seed, "gerador.contas")
    g_val = np_rng(seed, "gerador.valores")
    path.parent.mkdir(parents=True, exist_ok=True)
    chunk = 100_000
    with path.open("w", encoding="utf-8", newline="\n") as w:
        for start in range(0, count, chunk):
            n = min(chunk, count - start)
            accs = np_accounts(g_acc, n, accounts, distribution)
            vals = np_amounts(g_val, n)
            w.write("".join(f'{{"tx_id":{start + i + 1},"account_id":{int(a)},"amount":{fmt_amount(int(v))}}}\n'
                            for i, (a, v) in enumerate(zip(accs, vals, strict=True))))


def read_all(path: Path) -> list[Row]:
    if not path.exists():
        raise UsageError(f"dataset não encontrado: {path}; gere com: sentinela gerador")
    out = []
    with path.open("rb") as r:
        for line_no, line in enumerate(r, 1):
            if not line.strip():
                continue
            try:
                o = orjson.loads(line)
                amount = Decimal(str(o["amount"])).scaleb(2)
                if amount != amount.to_integral_value():
                    raise ValueError("amount com mais de 2 casas")
                out.append(Row(int(o["tx_id"]), int(o["account_id"]), int(amount)))
            except (orjson.JSONDecodeError, KeyError, TypeError, ValueError, InvalidOperation) as e:
                raise UsageError(f"dataset linha {line_no}: {e}") from None
    return out
