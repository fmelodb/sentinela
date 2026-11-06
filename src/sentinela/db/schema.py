"""Cria TX e DAILY_AGG no schema de ORACLE_USER a partir de db/01_schema.sql. Idempotente."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import oracledb

DEFAULT_SCHEMA = Path(__file__).resolve().parents[3] / "db" / "01_schema.sql"


def schema_path() -> Path:
    return Path(os.environ.get("SENTINELA_SCHEMA", DEFAULT_SCHEMA))


def split(script: str) -> list[str]:
    """Divide um script SQL simples (sem blocos PL/SQL) em comandos."""
    out, current = [], []
    for line in script.splitlines():
        stripped = line.strip()
        if stripped.startswith("--") or (not stripped and not current):
            continue
        if stripped.endswith(";"):
            current.append(line[: line.rindex(";")])
            stmt = "\n".join(current).strip()
            if stmt:
                out.append(stmt)
            current = []
        else:
            current.append(line)
    rest = "\n".join(current).strip()
    if rest:
        out.append(rest)
    return out


def apply(conn: oracledb.Connection, path: Path) -> None:
    with conn.cursor() as cur:
        for sql in split(path.read_text(encoding="utf-8")):
            cur.execute(sql)
            print(f"schema: ok - {sql.splitlines()[0]}", file=sys.stderr)
