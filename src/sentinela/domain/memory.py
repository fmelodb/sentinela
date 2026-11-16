"""Os mesmos agregados do SQL de leitura (db/read_sql.py), calculados em memória sobre eventos do domínio.

Serve de store da API nos testes sem banco e permite testar variables.compute contra os casos do Excel.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from datetime import timedelta

from sentinela.domain.events import Abr, T0, Trx, Vu
from sentinela.domain.variables import (FILTERS, SEC_1H, SEC_3H, SEC_5M, SEC_24H, SEC_92D, WINDOWS, Aggregates,
                                        FilterAgg)


def aggregate(t0: T0, trx: Iterable[Trx], abr: Iterable[Abr], vu: Iterable[Vu]) -> Aggregates:
    hi = t0.ts - timedelta(seconds=1)

    def lo(sec: int):
        return t0.ts - timedelta(seconds=sec)

    rows = [t for t in trx if t.cliente == t0.cliente and t.flags.base and lo(SEC_92D) <= t.ts <= hi]
    w24 = lambda t: t.ts >= lo(SEC_24H)  # noqa: E731
    w3h = lambda t: t.ts >= lo(SEC_3H)  # noqa: E731
    w5 = lambda t: t.ts >= lo(SEC_5M)  # noqa: E731
    mcc = lambda t: t.mcc == t0.mcc  # noqa: E731 - None == None, como a chave "NULL" do Excel
    com = lambda t: t.comercio == t0.comercio  # noqa: E731
    windows: dict[str, Callable[[Trx], bool]] = {
        "n24": w24, "n92": lambda t: True, "n3h": w3h,
        "n24_mcc": lambda t: w24(t) and mcc(t), "n92_mcc": mcc, "n5_com": lambda t: w5(t) and com(t)}
    filters: dict[str, Callable[[Trx], bool]] = {
        "f1_24": lambda t: t.flags.ap and w24(t),
        "f1_92": lambda t: t.flags.ap,
        "f2_24": lambda t: t.flags.ap and mcc(t) and w24(t),
        "f2_92": lambda t: t.flags.ap and mcc(t),
        "f3": lambda t: t.flags.re and w24(t),
        "f4": lambda t: t.flags.ap and w5(t) and com(t),
        "f7": lambda t: t.flags.ap_aus and w3h(t),
        "f8": lambda t: t.flags.re_npd,
        "f9_24": lambda t: t.flags.ap_mad and w24(t),
        "f9_92": lambda t: t.flags.ap_mad,
    }
    assert tuple(windows) == WINDOWS and tuple(filters) == FILTERS

    def agg(pred: Callable[[Trx], bool]) -> FilterAgg:
        sel = [t for t in rows if pred(t)]
        amounts = [t.monto for t in sel if t.monto is not None]
        return FilterAgg(len(sel), len(sel) - len(amounts), sum(amounts) if amounts else None,
                         max(amounts) if amounts else None)

    abr_secs = {a.ts for a in abr if a.cliente == t0.cliente and a.valido and lo(SEC_92D) <= a.ts <= hi}
    vus = [v for v in vu if v.cliente == t0.cliente and lo(SEC_92D) <= v.ts <= t0.ts]
    return Aggregates(
        n={k: sum(1 for t in rows if p(t)) for k, p in windows.items()},
        f={k: agg(p) for k, p in filters.items()},
        abr_1h=sum(1 for s in abr_secs if s >= lo(SEC_1H)), abr_92=len(abr_secs),
        vu_92=len(vus), cpass_92=sum(1 for v in vus if v.cpass))
