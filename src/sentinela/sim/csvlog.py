"""Log de latência por operação e o arquivo .meta.json ao lado, que o relatório lê junto com o CSV."""

from __future__ import annotations

import json
import sys
import traceback
from collections import Counter
from pathlib import Path
from typing import TextIO

HEADER = "ts_epoch_ms,program,operation,account_id,latency_us,db_us,status"
FLUSH_EVERY = 4096


def meta_path(csv: Path) -> Path:
    return csv.with_name(csv.stem + ".meta.json")


def write_meta(csv: Path, values: dict) -> None:
    meta_path(csv).write_text(json.dumps(values, indent=2, ensure_ascii=False), encoding="utf-8")


def update_meta(csv: Path, **values) -> None:
    m = read_meta(csv)
    m.update(values)
    write_meta(csv, m)


def read_meta(csv: Path) -> dict:
    return json.loads(meta_path(csv).read_text(encoding="utf-8"))


class CsvLog:
    """Acumula linhas em memória e grava em blocos: nada de I/O por requisição."""

    def __init__(self, path: Path, program: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.path = path
        self.program = program
        self._f = path.open("w", encoding="utf-8", newline="\n")
        self._f.write(HEADER + "\n")
        self._buf: list[str] = []

    def record(self, ts_ms: int, operation: str, account_id: int, latency_us: int, db_us: int | None,
               ok: bool) -> None:
        self._buf.append(f"{ts_ms},{self.program},{operation},{account_id},{latency_us},"
                         f"{'' if db_us is None else db_us},{'ok' if ok else 'error'}\n")
        if len(self._buf) >= FLUSH_EVERY:
            self.flush()

    def flush(self) -> None:
        if self._buf:
            self._f.write("".join(self._buf))
            self._buf.clear()
        self._f.flush()

    def close(self) -> None:
        self.flush()
        self._f.close()

    def __enter__(self) -> CsvLog:
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class ErrorReporter:
    """Os 10 primeiros erros com detalhe; depois, só a contagem por tipo, impressa no fim."""

    FULL = 10

    def __init__(self, out: TextIO = sys.stderr):
        self.out = out
        self.total = 0
        self.by_type: Counter[str] = Counter()

    def report(self, e: BaseException) -> None:
        self.total += 1
        self.by_type[type(e).__name__] += 1
        if self.total <= self.FULL:
            print(f"erro #{self.total}: {''.join(traceback.format_exception_only(e)).strip()}", file=self.out)

    def summary(self) -> None:
        if self.total:
            print(f"total de erros: {self.total} {dict(sorted(self.by_type.items()))}", file=self.out)
