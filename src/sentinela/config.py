"""Configuração vinda do ambiente, para credenciais não aparecerem em argumentos nem em logs."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from sentinela.errors import UsageError

DEFAULT_ZONE = "America/Sao_Paulo"
DEFAULT_API_URL = "http://localhost:8000"


def _clean(v: str | None) -> str | None:
    return None if v is None or not v.strip() else v.strip()


def _positive(vars: Mapping[str, str], name: str, default: int) -> int:
    raw = _clean(vars.get(name))
    if raw is None:
        return default
    try:
        v = int(raw)
    except ValueError:
        raise UsageError(f"{name} precisa ser inteiro: {raw}") from None
    if v <= 0:
        raise UsageError(f"{name} precisa ser positivo: {raw}")
    return v


@dataclass(frozen=True)
class Env:
    oracle_dsn: str | None
    oracle_user: str | None
    oracle_password: str | None
    zone: ZoneInfo
    api_url: str
    api_workers: int
    db_pool_size: int

    @staticmethod
    def from_environ(vars: Mapping[str, str] | None = None) -> Env:
        vars = os.environ if vars is None else vars
        z = _clean(vars.get("FS_ZONE")) or DEFAULT_ZONE
        try:
            zone = ZoneInfo(z)
        except (ZoneInfoNotFoundError, ValueError):
            raise UsageError(f"FS_ZONE inválido: {z}") from None
        return Env(
            oracle_dsn=_clean(vars.get("ORACLE_DSN")),
            oracle_user=_clean(vars.get("ORACLE_USER")),
            oracle_password=_clean(vars.get("ORACLE_PASSWORD")),
            zone=zone,
            api_url=(_clean(vars.get("API_URL")) or DEFAULT_API_URL).rstrip("/"),
            api_workers=_positive(vars, "API_WORKERS", 4),
            db_pool_size=_positive(vars, "DB_POOL_SIZE", 8),
        )

    def require_oracle(self) -> None:
        missing = [n for n, v in (("ORACLE_DSN", self.oracle_dsn), ("ORACLE_USER", self.oracle_user),
                                  ("ORACLE_PASSWORD", self.oracle_password)) if v is None]
        if missing:
            raise UsageError("variáveis de ambiente ausentes: " + ", ".join(missing))

    def __repr__(self) -> str:
        return (f"Env(oracle_dsn={self.oracle_dsn!r}, oracle_user={self.oracle_user!r}, oracle_password=***, "
                f"zone={self.zone}, api_url={self.api_url!r})")
