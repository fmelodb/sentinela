"""Cálculo de referência das 10 variáveis, transcrição direta das fórmulas do Excel de validação
(docs/specs/variaveis-xgboost.md). Trabalha sobre linhas com os nomes de coluna da fonte e não usa o SQL nem
sentinela.domain: é o voto independente contra o qual o Oracle é conferido.

Instantes são datetimes sem fuso, em hora local (como no Excel). Valores ausentes são None.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from datetime import datetime

Row = Mapping[str, object]

NAMES = ("CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED", "CLI_MCC_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED",
         "CLI_GL_LAST_24_HOUR_PROM_AMOUNT_TRX_NOT_APPROVED", "CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED",
         "CLI_ABR_LAST_1_HOUR_E_92_DAY_DISTINCT_CLIENT", "CLI_VU_LAST_92_DAY_R_N_CPASS",
         "CLI_A_LAST_3_HOUR_AMOUNT_TRX_APPROVED_MAX", "CLI_NP_LAST_92_DAY_PROM_AMOUNT_TRX_D_NOT_APPROVED",
         "CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_M_APPROVED_MAX", "R_AMOUNT_TRX_CLI_GL_92_DAY_AMOUNT_TRX_APPROVED")

_PRESENTE = {9, 20, 21, 22, 23, 50, 51, 52, 53, 70, 71, 72, 73, 800, 801, 802, 803, 900, 901, 902, 903, 910, 911,
             912, 913}
_EPOCH = datetime(1970, 1, 1)
_ABR_ATTRS = ("S_LENGUAGE", "N_COLOR", "N_TIME_ZONE", "S_DEVICE_MODEL", "S_DEVICE_NAME", "S_DEVICE_MAKER",
              "N_GPS_COMPATIBLE", "S_VERSION_OS", "S_DEVICE_ID", "S_RANDOM_SEED")


def seconds(ts: datetime) -> int:
    """ROUND((fecha - DATE(1970,1,1)) * 86400, 0)."""
    return round((ts - _EPOCH).total_seconds())


def _ts(v: object) -> datetime:
    return v if isinstance(v, datetime) else datetime.fromisoformat(str(v))


def _blank(v: object) -> bool:
    return v is None or v == ""


def _mcc_key(v: object) -> str:
    return "NULL" if _blank(v) else str(v)


class _Trx:
    """As colunas auxiliares R..AW de Input TRX para uma linha, dado o T0 do cliente."""

    def __init__(self, r: Row, t0_sec: int, t0_mcc: str, t0_com: object):
        self.amount = None if _blank(r["F_AMOUNT_TRANSACTION"]) else r["F_AMOUNT_TRANSACTION"]
        ts = _ts(r["D_TRANSMISSION_DATE_AND_TIME"])
        hora = ts.hour
        base = r["ORIGEN"] == "atmpos_trxs" and r["N_MTI"] == 200
        global_tx = r["N_PCODE"] in (3000, 2000, 6000)
        aprobado = r["S_RESPONSE_CODE"] == "00"
        presente = r["N_ENTRY_MODE"] in _PRESENTE
        nombre = r["S_CARD_ACCEPTOR_NAME"]
        nacional = r["N_ACQUIRING_INSTITUTION_COUNTRY_CODE"] == 152 or (
            isinstance(nombre, str) and "WEBPAY" in nombre)
        franja = "TARDE-NOCHE" if hora > 14 else "DIURNO" if hora > 5 else "MADRUGADA"
        delta = t0_sec - seconds(ts)
        self.aprobada_gl = base and global_tx and aprobado
        self.rechazada_gl = base and global_tx and not aprobado
        self.en_5min = base and 1 <= delta <= 300
        self.en_3h = base and 1 <= delta <= 10800
        self.en_24h = base and 1 <= delta <= 86400
        self.en_92d = base and 1 <= delta <= 7948800
        self.mismo_mcc = _mcc_key(r["N_MERCHANT_TYPE"]) == t0_mcc
        com = r["S_CARD_ACCEPT_EXT_ID_CODE"]
        self.mismo_comercio = (com if not _blank(com) else None) == (t0_com if not _blank(t0_com) else None)
        self.v01_num = self.en_24h and self.aprobada_gl
        self.v01_den = self.en_92d and self.aprobada_gl
        self.v02_num = self.v01_num and self.mismo_mcc
        self.v02_den = self.v01_den and self.mismo_mcc
        self.v03 = self.en_24h and self.rechazada_gl
        self.v04 = self.en_5min and self.aprobada_gl and self.mismo_comercio
        self.v07 = self.en_3h and self.aprobada_gl and not presente
        self.v08 = self.en_92d and self.rechazada_gl and nacional and presente and franja == "DIURNO"
        self.v09_num = self.v01_num and franja == "MADRUGADA"
        self.v09_den = self.v01_den and franja == "MADRUGADA"


def _count(rows: list[_Trx], flag: str) -> int:
    return sum(1 for r in rows if getattr(r, flag))


def _count_null(rows: list[_Trx], flag: str) -> int:
    return sum(1 for r in rows if getattr(r, flag) and r.amount is None)


def _sum(rows: list[_Trx], flag: str):
    return sum(r.amount for r in rows if getattr(r, flag) and r.amount is not None)


def _max0(rows: list[_Trx], flag: str):
    """MAX(0, MAXIFS(...)): MAXIFS sem linhas devolve 0."""
    return max([0] + [r.amount for r in rows if getattr(r, flag) and r.amount is not None])


def _ratio(num, den):
    if num is None or den is None or den == 0:
        return None
    return num / den


def compute(t0: Row, trx: Iterable[Row], abr: Iterable[Row], vu: Iterable[Row]) -> dict[str, float | int | None]:
    """t0: N_ID_CUSTOMER, D_TRANSMISSION_DATE_AND_TIME, F_AMOUNT_TRANSACTION, N_MERCHANT_TYPE,
    S_CARD_ACCEPT_EXT_ID_CODE. trx/abr/vu: linhas de qualquer cliente; só as do cliente do T0 contam."""
    cli = t0["N_ID_CUSTOMER"]
    t0_sec = seconds(_ts(t0["D_TRANSMISSION_DATE_AND_TIME"]))
    t0_mcc = _mcc_key(t0["N_MERCHANT_TYPE"])
    rows = [_Trx(r, t0_sec, t0_mcc, t0["S_CARD_ACCEPT_EXT_ID_CODE"]) for r in trx if r["N_ID_CUSTOMER"] == cli]

    n24, n92, n3h = _count(rows, "en_24h"), _count(rows, "en_92d"), _count(rows, "en_3h")

    def gated_sum(n_window: int, flag: str):
        return None if n_window - _count_null(rows, flag) == 0 else _sum(rows, flag)

    # V1
    v1_num, v1_den = gated_sum(n24, "v01_num"), gated_sum(n92, "v01_den")
    v1 = _ratio(v1_num, v1_den)
    # V2: as janelas contam só linhas do mesmo MCC
    n24_mcc = sum(1 for r in rows if r.en_24h and r.mismo_mcc)
    n92_mcc = sum(1 for r in rows if r.en_92d and r.mismo_mcc)
    v2 = _ratio(gated_sum(n24_mcc, "v02_num"), gated_sum(n92_mcc, "v02_den"))
    # V3
    v3_den = None if n24 == 0 else _count(rows, "v03")
    v3 = _ratio(gated_sum(n24, "v03"), v3_den)
    # V4
    n5_com = sum(1 for r in rows if r.en_5min and r.mismo_comercio)
    v4 = 0 if n5_com == 0 else _count(rows, "v04")
    # V5: notificações ABR válidas, distintas por segundo, do cliente
    seen: set[int] = set()
    v5_num = v5_den = 0
    for r in abr:
        if r["N_ID_CUSTOMER"] != cli or all(_blank(r[c]) for c in _ABR_ATTRS):
            continue
        sec = seconds(_ts(r["D_TRANSMISSION_DATE_AND_TIME"]))
        if sec in seen:
            continue
        seen.add(sec)
        delta = t0_sec - sec
        if 1 <= delta <= 7948800:
            v5_den += 1
            if delta <= 3600:
                v5_num += 1
    v5 = None if v5_den == 0 else v5_num / v5_den
    # V6: VU inclui o mesmo segundo do T0
    vu_92 = cpass_92 = 0
    for r in vu:
        if r["N_ID_CUSTOMER"] != cli:
            continue
        delta = t0_sec - seconds(_ts(r["D_TRANSMISSION_DATE_AND_TIME"]))
        if 0 <= delta <= 7948800:
            vu_92 += 1
            if r["N_TRANSACTION_TYPE"] in (999901, 999903):
                cpass_92 += 1
    v6 = None if cpass_92 == 0 or vu_92 == 0 else cpass_92 / vu_92
    # V7
    v7 = 0 if n3h - _count_null(rows, "v07") == 0 else _max0(rows, "v07")
    # V8
    v8_den = None if n92 == 0 else _count(rows, "v08")
    v8 = _ratio(gated_sum(n92, "v08"), v8_den)
    # V9
    v9_num = None if n24 - _count_null(rows, "v09_num") == 0 else _max0(rows, "v09_num")
    v9_den = None if n92 - _count_null(rows, "v09_den") == 0 else _max0(rows, "v09_den")
    v9 = _ratio(v9_num, v9_den)
    # V10: reutiliza o denominador de V1
    amount = t0["F_AMOUNT_TRANSACTION"]
    v10 = None if _blank(amount) else _ratio(amount, v1_den)

    return dict(zip(NAMES, (v1, v2, v3, v4, v5, v6, v7, v8, v9, v10), strict=True))
