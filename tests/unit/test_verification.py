from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from sentinela.domain.agg import Transaction, WindowAgg
from sentinela.domain.windows import UTC, limits
from sentinela.verification import data, reference

SP = ZoneInfo("America/Sao_Paulo")
NOW = datetime(2026, 10, 29, 15, 7, 30, 123456, tzinfo=UTC)
US = timedelta(microseconds=1)


def test_referencia_respeita_limites_abertos_e_fechados():
    lo = NOW - timedelta(minutes=1)
    txs = [Transaction(1, 1, lo, 100), Transaction(2, 1, lo + US, 200), Transaction(3, 1, NOW, 300),
           Transaction(4, 1, NOW + US, 400)]
    v = reference.compute(txs, NOW, SP)
    assert v.aggs[0] == WindowAgg(2, 500, 200, 300)
    assert v.aggs[1] == WindowAgg(3, 600, 100, 300)


def test_referencia_janela_de_dias_inclui_hoje_desde_meia_noite_local():
    today_start = limits(NOW, SP).today_start
    txs = [Transaction(1, 1, today_start - US, 100), Transaction(2, 1, today_start, 200)]
    v = reference.compute(txs, NOW, SP)
    assert v.aggs[15].cnt == 2  # 7d inclui ontem
    lim7 = limits(NOW, SP).closed_lo[0]
    from sentinela.domain.windows import day_start

    edge = day_start(lim7, SP)
    v2 = reference.compute([Transaction(1, 1, edge - US, 1), Transaction(2, 1, edge, 2)], NOW, SP)
    assert v2.aggs[15].cnt == 1


def test_classes_e_fronteiras():
    txs = data.generate(40, 42, NOW, SP)
    assert txs == sorted(txs, key=lambda t: (t.ts, t.tx_id))
    by = {}
    for t in txs:
        by.setdefault(t.account_id, []).append(t)
    for acc, ts in by.items():
        cls = data.account_class(acc)
        if cls == data.SILENT_LAST_HOUR:
            assert all(t.ts <= NOW - timedelta(hours=1) for t in ts)
        if cls == data.TODAY_ONLY:
            assert all(t.ts >= limits(NOW, SP).today_start for t in ts)
        if cls == data.BOUNDARIES:
            assert len(ts) == len(data.boundaries(NOW, SP))
    b = data.boundaries(NOW, SP)
    assert NOW + US in b and NOW - timedelta(hours=72) in b and len(b) == len(set(b))


def test_geracao_deterministica():
    assert data.generate(20, 1, NOW, SP) == data.generate(20, 1, NOW, SP)
