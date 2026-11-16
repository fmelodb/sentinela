"""Conversões de valores para JSON."""

from __future__ import annotations

from datetime import datetime


def iso_local(t: datetime) -> str:
    """Hora local sem fuso, ao segundo: 2026-09-30T12:00:00."""
    return t.isoformat(timespec="seconds")
