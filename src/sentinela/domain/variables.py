"""Catálogo das 10 variáveis e o cálculo a partir dos agregados brutos que a leitura do Oracle devolve.

As regras de NULL seguem as fórmulas do Excel (docs/specs/variaveis-xgboost.md, seção 4): várias dependem do
número de linhas da janela inteira, não só das que passam no filtro da variável.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

VERSAO = "excel-2026-11-13"

NAMES: tuple[str, ...] = (
    "CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED",
    "CLI_MCC_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED",
    "CLI_GL_LAST_24_HOUR_PROM_AMOUNT_TRX_NOT_APPROVED",
    "CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED",
    "CLI_ABR_LAST_1_HOUR_E_92_DAY_DISTINCT_CLIENT",
    "CLI_VU_LAST_92_DAY_R_N_CPASS",
    "CLI_A_LAST_3_HOUR_AMOUNT_TRX_APPROVED_MAX",
    "CLI_NP_LAST_92_DAY_PROM_AMOUNT_TRX_D_NOT_APPROVED",
    "CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_M_APPROVED_MAX",
    "R_AMOUNT_TRX_CLI_GL_92_DAY_AMOUNT_TRX_APPROVED",
)

# Janelas em segundos antes de T0. TRX e ABR: 1 ≤ delta ≤ N. VU: 0 ≤ delta ≤ N.
SEC_5M = 300
SEC_1H = 3600
SEC_3H = 10_800
SEC_24H = 86_400
SEC_92D = 92 * 86_400

# Filtros com agregado próprio (contagem, nulos, soma e máximo do monto).
FILTERS: tuple[str, ...] = ("f1_24", "f1_92", "f2_24", "f2_92", "f3", "f4", "f7", "f8", "f9_24", "f9_92")
# Contagens de linhas base por janela, usadas nas regras de NULL.
WINDOWS: tuple[str, ...] = ("n24", "n92", "n3h", "n24_mcc", "n92_mcc", "n5_com")

Value = int | float | None


@dataclass(frozen=True, slots=True)
class FilterAgg:
    cnt: int = 0                 # linhas que passam no filtro (inclui monto nulo)
    nnull: int = 0               # dessas, com monto nulo
    sum: Decimal | None = None   # soma dos montos não nulos (None se não há)
    max: Decimal | None = None   # máximo dos montos não nulos (None se não há)


@dataclass(frozen=True, slots=True)
class Aggregates:
    n: dict[str, int]
    f: dict[str, FilterAgg]
    abr_1h: int = 0
    abr_92: int = 0
    vu_92: int = 0
    cpass_92: int = 0


def _gate_sum(a: Aggregates, window: str, flt: str) -> Decimal | None:
    """SUMIFS protegido: NULL se a janela não tem linhas além das nulas do filtro; senão a soma (0 se vazia)."""
    f = a.f[flt]
    return None if a.n[window] - f.nnull == 0 else (f.sum or Decimal(0))


def _gate_max0(a: Aggregates, window: str, flt: str) -> Decimal | None:
    f = a.f[flt]
    return None if a.n[window] - f.nnull == 0 else max(Decimal(0), f.max or Decimal(0))


def _ratio(num: Decimal | int | None, den: Decimal | int | None) -> float | None:
    if num is None or den is None or den == 0:
        return None
    return float(Decimal(num) / Decimal(den))


def compute(a: Aggregates, monto_t0: Decimal | None) -> dict[str, Value]:
    v1_den = _gate_sum(a, "n92", "f1_92")
    v1 = _ratio(_gate_sum(a, "n24", "f1_24"), v1_den)
    v2 = _ratio(_gate_sum(a, "n24_mcc", "f2_24"), _gate_sum(a, "n92_mcc", "f2_92"))
    v3 = _ratio(_gate_sum(a, "n24", "f3"), None if a.n["n24"] == 0 else a.f["f3"].cnt)
    v4 = 0 if a.n["n5_com"] == 0 else a.f["f4"].cnt
    v5 = None if a.abr_92 == 0 else a.abr_1h / a.abr_92
    v6 = None if a.cpass_92 == 0 or a.vu_92 == 0 else a.cpass_92 / a.vu_92
    f7 = a.f["f7"]
    v7: Value = 0 if a.n["n3h"] - f7.nnull == 0 else float(max(Decimal(0), f7.max or Decimal(0)))
    v8 = _ratio(_gate_sum(a, "n92", "f8"), None if a.n["n92"] == 0 else a.f["f8"].cnt)
    v9 = _ratio(_gate_max0(a, "n24", "f9_24"), _gate_max0(a, "n92", "f9_92"))
    v10 = None if monto_t0 is None else _ratio(monto_t0, v1_den)
    return dict(zip(NAMES, (v1, v2, v3, v4, v5, v6, v7, v8, v9, v10), strict=True))
