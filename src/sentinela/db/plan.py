"""Plano esperado da leitura: os três índices globais cobertos, sem acesso às tabelas. Se o plano for outro, a
rodada de benchmark não vale."""

from __future__ import annotations

INDEXES = ("TRX_CLI_TS_IX", "ABR_CLI_TS_IX", "VU_CLI_TS_IX")


def problems(plan: list[str]) -> list[str]:
    text = "\n".join(plan)
    out = [f"índice {ix} não usado" for ix in INDEXES if ix not in text]
    if "TABLE ACCESS" in text:
        out.append("TABLE ACCESS no plano: o índice coberto não foi suficiente")
    return out
