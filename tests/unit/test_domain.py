"""Domínio: parsing com os nomes da fonte, classificação de TRX, agregação em memória e as 10 variáveis contra
os casos do Excel."""

import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest

from sentinela.verification import scenarios
from sentinela.api.models import AbrIn, T0In, TrxIn, VuIn
from sentinela.domain import memory, variables
from sentinela.domain.events import Abr, Trx, Vu, local_second

SCL = ZoneInfo("America/Santiago")
FIXTURE = scenarios.excel_fixture()


def fixture_events():
    trx = [TrxIn.model_validate(r).to_domain(SCL) for r in FIXTURE["trx"]]
    abr = [AbrIn.model_validate(r).to_domain(SCL) for r in FIXTURE["abr"]]
    vu = [VuIn.model_validate(r).to_domain(SCL) for r in FIXTURE["vu"]]
    return trx, abr, vu


def assert_vars(got: dict, want: dict) -> None:
    assert list(got) == list(variables.NAMES)
    for name, w in want.items():
        if w is None:
            assert got[name] is None, name
        else:
            assert got[name] == pytest.approx(w, rel=1e-12, abs=1e-12), name


@pytest.mark.parametrize("caso", FIXTURE["casos"], ids=lambda c: c["caso"])
def test_variaveis_batem_com_excel(caso):
    trx, abr, vu = fixture_events()
    t0_row = next(r for r in FIXTURE["trx"] if r["ID_TRX"] == caso["id_trx"])
    t0 = T0In.model_validate(t0_row).to_domain(SCL)
    got = variables.compute(memory.aggregate(t0, trx, abr, vu), t0.monto)
    assert_vars(got, caso["esperado"])


def test_nomes_iguais_aos_do_excel():
    assert list(variables.NAMES) == FIXTURE["variaveis"]


def test_local_second_converte_fuso_e_arredonda():
    utc = datetime(2026, 9, 30, 15, 0, 0, 500_000, tzinfo=timezone.utc)
    assert local_second(utc, SCL) == datetime(2026, 9, 30, 12, 0, 1)  # -03 em setembro
    assert local_second(datetime(2026, 9, 30, 12, 0, 0, 499_999), SCL) == datetime(2026, 9, 30, 12, 0, 0)


def _trx(**kw):
    base = dict(id_trx="1", cliente="C", ts=datetime(2026, 9, 1, 10), monto=Decimal(10), response_code="00",
                pcode=3000, entry_mode=9, pais=152, origen="atmpos_trxs", mti=200)
    return Trx(**(base | kw))


def test_classificacao_trx():
    assert _trx().flags.ap and not _trx().flags.re
    assert _trx(response_code=None).flags.re  # nula = rechazada
    assert not _trx(pcode=1000).flags.ap and not _trx(pcode=1000).flags.re and _trx(pcode=1000).flags.base
    assert not _trx(mti=210).flags.base and not _trx(origen="x").flags.base
    assert _trx(entry_mode=None).flags.ap_aus and not _trx().flags.ap_aus
    assert _trx(ts=datetime(2026, 9, 1, 5, 59, 59)).flags.ap_mad
    assert not _trx(ts=datetime(2026, 9, 1, 22)).flags.ap_mad  # 22h é tarde-noche no Excel
    npd = dict(response_code="05", ts=datetime(2026, 9, 1, 14, 59, 59))
    assert _trx(**npd).flags.re_npd
    assert _trx(**npd, pais=840, nombre_comercio="WEBPAY.CL X").flags.re_npd
    assert not _trx(**npd, pais=840, nombre_comercio="webpay").flags.re_npd  # FIND é sensível a maiúsculas
    assert not _trx(**(npd | {"ts": datetime(2026, 9, 1, 15)})).flags.re_npd


def test_trx_exige_hora_local_ao_segundo():
    with pytest.raises(ValueError):
        _trx(ts=datetime(2026, 9, 1, 10, tzinfo=timezone.utc))
    with pytest.raises(ValueError):
        _trx(ts=datetime(2026, 9, 1, 10, 0, 0, 1))


def test_abr_valido_e_vu_cpass():
    assert not Abr("C", datetime(2026, 1, 1)).valido
    assert Abr("C", datetime(2026, 1, 1), random_seed="S").valido
    assert Vu("C", datetime(2026, 1, 1), tipo=999903).cpass and not Vu("C", datetime(2026, 1, 1), tipo=999910).cpass


def test_t0_aceita_corpo_de_trx_com_extras():
    t0 = T0In.model_validate({"N_ID_CUSTOMER": 42, "D_TRANSMISSION_DATE_AND_TIME": "2026-09-30T12:00:00",
                              "F_AMOUNT_TRANSACTION": 150.5, "ES_T0": 1, "S_CARD_ACCEPT_EXT_ID_CODE": ""})
    d = t0.to_domain(SCL)
    assert d.cliente == "42" and d.comercio is None and d.monto == Decimal("150.5")
