"""A referência reproduz os 60 valores (6 casos × 10 variáveis) calculados pelo Excel de validação."""

import json
from pathlib import Path

import pytest

from sentinela.verification import scenarios
from sentinela.verification import reference

FIXTURE = scenarios.excel_fixture()


def test_nomes_na_ordem_do_output():
    assert list(reference.NAMES) == FIXTURE["variaveis"]


@pytest.mark.parametrize("caso", FIXTURE["casos"], ids=lambda c: c["caso"])
def test_caso_excel(caso):
    t0 = next(t for t in FIXTURE["trx"] if t["ID_TRX"] == caso["id_trx"])
    got = reference.compute(t0, FIXTURE["trx"], FIXTURE["abr"], FIXTURE["vu"])
    for name, want in caso["esperado"].items():
        if want is None:
            assert got[name] is None, name
        else:
            assert got[name] == pytest.approx(want, rel=1e-12, abs=1e-12), name
