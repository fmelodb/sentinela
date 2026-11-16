"""História sintética das três fontes, direto no banco (sem passar pela API): `days` dias até agora, em hora
local de FS_ZONE. Destrutivo: esvazia TRX, ABR e VU antes. No fim coleta estatísticas e confere o plano real da
leitura sobre o volume carregado; plano errado invalida a rodada."""

from __future__ import annotations

import sys
import time
from datetime import datetime, timedelta

import oracledb

from sentinela.db import plan, rows
from sentinela.db.maintenance import Maintenance
from sentinela.domain.events import T0
from sentinela.errors import InvalidRun
from sentinela.sim.synth import Synth, cliente_id, comercio_of, mcc_of

MAX_DAYS = 92


def run(conn: oracledb.Connection, now: datetime, clients: int, days: int, seed: int, distribution: str,
        per_day: dict[str, float]) -> dict[str, int]:
    """now: hora local sem fuso. per_day: eventos por cliente por dia de cada fonte."""
    t_start = time.perf_counter()

    def step(msg: str) -> None:
        print(f"backfill [{time.perf_counter() - t_start:7.1f}s] {msg}", file=sys.stderr)

    m = Maintenance(conn)
    m.reset()
    step("TRX, ABR e VU esvaziadas")
    s = Synth(clients, distribution, seed, "backfill")
    now = now.replace(microsecond=0)
    first = now - timedelta(days=days)
    total = {"trx": 0, "abr": 0, "vu": 0}
    for k in range(days):
        start = first + timedelta(days=k)
        span = int((min(start + timedelta(days=1), now) - start).total_seconds())
        n = {f: round(clients * per_day[f] * span / 86_400) for f in total}
        for fonte, kind, events in (
                ("trx", rows.TRX, s.trx(start, span, n["trx"], total["trx"] + 1, "B")),
                ("abr", rows.ABR, s.abr(start, span, n["abr"], 0, None)),
                ("vu", rows.VU, s.vu(start, span, n["vu"], 0, None))):
            r = m.bulk_insert(kind, events)
            if r.erros:
                raise InvalidRun(f"backfill {fonte}: {len(r.erros)} erro(s), o primeiro: {r.erros[0]}")
            total[fonte] += r.novas
        if k % 10 == 9 or k == days - 1:
            step(f"até {start.date()}: " + ", ".join(f"{v} {f.upper()}" for f, v in total.items()))
    m.gather_stats()
    step("estatísticas coletadas")

    # Plano real da leitura para um cliente quente (o de mais eventos), no instante atual.
    m.features(T0(cliente_id(1), now, None, mcc_of(1, 0), comercio_of(1, 0)))
    lines = m.read_plan()
    problems = plan.problems(lines)
    if problems:
        print("\n".join(lines), file=sys.stderr)
        raise InvalidRun("plano de leitura inválido: " + "; ".join(problems))
    step("plano de leitura ok (três índices cobertos, sem acesso às tabelas)")
    return total
