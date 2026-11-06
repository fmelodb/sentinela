from datetime import date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest

from sentinela.domain.agg import EMPTY, NAMES, FeatureVector, Transaction, WindowAgg
from sentinela.domain.windows import FIRST_DAY_WINDOW, ROLLING, SIZE, SUFFIXES, UTC, day_start, limits

SP = ZoneInfo("America/Sao_Paulo")
NOW = datetime(2026, 10, 29, 15, 7, 30, 123456, tzinfo=UTC)


def test_catalogo_tem_19_janelas_na_ordem_da_spec():
    assert SIZE == 19 and FIRST_DAY_WINDOW == 15
    assert SUFFIXES[0] == "1m" and SUFFIXES[14] == "72h" and SUFFIXES[-1] == "92d"


def test_nomes_95_na_ordem():
    assert len(NAMES) == 95
    assert NAMES[:5] == ("cnt_1m", "sum_1m", "min_1m", "max_1m", "avg_1m")
    assert NAMES[-1] == "avg_92d"


def test_limites_deslizantes_ao_microssegundo():
    lim = limits(NOW, SP)
    assert lim.rolling_lo[0] == NOW - timedelta(minutes=1)
    assert lim.oldest_rolling == NOW - timedelta(hours=72)
    lo = lim.rolling_lo[0]
    assert not lim.in_rolling(0, lo)
    assert lim.in_rolling(0, lo + timedelta(microseconds=1))
    assert lim.in_rolling(0, NOW)
    assert not lim.in_rolling(0, NOW + timedelta(microseconds=1))


def test_dia_corrente_e_dias_fechados_no_fuso():
    lim = limits(NOW, SP)
    assert lim.today == date(2026, 10, 29)
    assert lim.today_start == datetime(2026, 10, 29, 3, 0, tzinfo=UTC)  # 00:00 em São Paulo (UTC-3)
    assert lim.closed_lo[0] == date(2026, 10, 23)  # 7d = 6 dias fechados + hoje
    assert lim.oldest_closed == date(2026, 10, 29) - timedelta(days=91)
    assert lim.in_closed(0, date(2026, 10, 28)) and not lim.in_closed(0, date(2026, 10, 29))
    assert lim.in_today(lim.today_start) and not lim.in_today(lim.today_start - timedelta(microseconds=1))


def test_virada_do_dia_local_antes_da_utc():
    # 01:00 UTC de 30/10 ainda é 29/10 em São Paulo.
    lim = limits(datetime(2026, 10, 30, 1, 0, tzinfo=UTC), SP)
    assert lim.today == date(2026, 10, 29)
    assert day_start(date(2026, 10, 30), SP) == datetime(2026, 10, 30, 3, 0, tzinfo=UTC)


def test_limites_exigem_fuso():
    with pytest.raises(ValueError):
        limits(datetime(2026, 1, 1), SP)


def test_window_agg_plus():
    a = WindowAgg.of(100).plus(WindowAgg.of(50))
    assert a == WindowAgg(2, 150, 50, 100)
    assert EMPTY.plus(a) == a and a.plus(EMPTY) == a


def test_window_agg_invariantes():
    with pytest.raises(ValueError):
        WindowAgg(0, 1, None, None)
    with pytest.raises(ValueError):
        WindowAgg(1, 1, None, 1)


def test_transacao_valida():
    with pytest.raises(ValueError):
        Transaction(1, 0, NOW, 100)
    with pytest.raises(ValueError):
        Transaction(1, 1, NOW, 0)
    with pytest.raises(ValueError):
        Transaction(1, 1, datetime(2026, 1, 1), 100)


def test_vetor_valores_e_json():
    aggs = [EMPTY] * SIZE
    aggs[0] = WindowAgg(3, 1000, 100, 700)  # avg = 10 / 3 = 3,3333
    v = FeatureVector(tuple(aggs))
    values = v.values()
    assert values[:5] == [3, Decimal("10.00"), Decimal("1.00"), Decimal("7.00"), Decimal("3.3333")]
    assert values[5:10] == [0, Decimal("0.00"), None, None, None]
    j = v.to_json()
    assert list(j) == list(NAMES)
    assert j["cnt_1m"] == 3 and j["sum_1m"] == 10.0 and j["avg_1m"] == 3.3333 and j["min_5m"] is None


def test_media_half_even():
    aggs = [EMPTY] * SIZE
    aggs[0] = WindowAgg(8, 1, 0, 1)  # 0,01 / 8 = 0,00125 → 0,0012
    assert FeatureVector(tuple(aggs)).values()[4] == Decimal("0.0012")


def test_vetor_exige_19_janelas():
    with pytest.raises(ValueError):
        FeatureVector((EMPTY,) * (len(ROLLING)))
