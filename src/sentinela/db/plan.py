"""Plano esperado da leitura: poda de partições sobre o índice local coberto, PK da IOT para DAILY_AGG e
nenhum acesso à tabela. Se o plano for outro, a rodada não vale."""

from __future__ import annotations


def problems(plan: list[str]) -> list[str]:
    text = "\n".join(plan)
    out = []
    if "PARTITION RANGE ITERATOR" not in text:
        out.append("sem PARTITION RANGE ITERATOR: a leitura não poda as partições de TX")
    if "TX_ACC_TS_AMT_IX" not in text:
        out.append("índice TX_ACC_TS_AMT_IX não usado")
    if "DAILY_AGG_PK" not in text:
        out.append("DAILY_AGG_PK não usado")
    if "TABLE ACCESS" in text:
        out.append("TABLE ACCESS no plano: o índice coberto não foi suficiente")
    return out
