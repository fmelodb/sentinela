"""Extrai os casos sintéticos do Excel de validação para src/sentinela/verification/excel_casos.json.

Uso (uma vez; o JSON é versionado):
    python scripts/extrair_excel.py docs/artifacts/Validacion_variables_POC_Fraude_ML_casos_sinteticos.xlsx \
        src/sentinela/verification/excel_casos.json

Lê só as colunas de entrada das abas Input TRX / ABR / VU (as auxiliares são fórmulas) e os valores em cache
das 10 variáveis na aba Output. O texto "NULL" do Excel vira null; instantes ficam como ISO sem fuso (o Excel
não tem fuso: são hora local).
"""

from __future__ import annotations

import json
import sys
from datetime import datetime
from pathlib import Path

import openpyxl

TRX_COLS = ["CASO", "N_ID_CUSTOMER", "ID_TRX", "ES_T0", "FECHA_INFORMACION", "D_TRANSMISSION_DATE_AND_TIME",
            "F_AMOUNT_TRANSACTION", "S_RESPONSE_CODE", "N_PCODE", "N_ENTRY_MODE",
            "N_ACQUIRING_INSTITUTION_COUNTRY_CODE", "S_CARD_ACCEPTOR_NAME", "N_MERCHANT_TYPE",
            "S_CARD_ACCEPT_EXT_ID_CODE", "ORIGEN", "N_MTI", "COMENTARIO_CASO"]
ABR_COLS = ["CASO", "N_ID_CUSTOMER", "D_TRANSMISSION_DATE_AND_TIME", "FECHA_INFORMACION", "S_LENGUAGE", "N_COLOR",
            "N_TIME_ZONE", "S_DEVICE_MODEL", "S_DEVICE_NAME", "S_DEVICE_MAKER", "N_GPS_COMPATIBLE", "S_VERSION_OS",
            "S_DEVICE_ID", "S_RANDOM_SEED", "COMENTARIO_CASO"]
VU_COLS = ["CASO", "N_ID_CUSTOMER", "D_TRANSMISSION_DATE_AND_TIME", "FECHA_INFORMACION", "S_IP_ADDRESS",
           "S_CHANNEL_NAME", "N_TRANSACTION_TYPE", "COMENTARIO_CASO"]
NAMES = ["CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED", "CLI_MCC_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_APPROVED",
         "CLI_GL_LAST_24_HOUR_PROM_AMOUNT_TRX_NOT_APPROVED", "CLI_COM_GL_LAST_5_MIN_N_TRX_APPROVED",
         "CLI_ABR_LAST_1_HOUR_E_92_DAY_DISTINCT_CLIENT", "CLI_VU_LAST_92_DAY_R_N_CPASS",
         "CLI_A_LAST_3_HOUR_AMOUNT_TRX_APPROVED_MAX", "CLI_NP_LAST_92_DAY_PROM_AMOUNT_TRX_D_NOT_APPROVED",
         "CLI_GL_LAST_24_HOUR_E_92_DAY_AMOUNT_TRX_M_APPROVED_MAX", "R_AMOUNT_TRX_CLI_GL_92_DAY_AMOUNT_TRX_APPROVED"]


def _value(v):
    if isinstance(v, datetime):
        return v.isoformat()
    if v == "":
        return None
    return v


def _rows(ws, cols: list[str]) -> list[dict]:
    header = [c.value for c in ws[1]]
    idx = [header.index(c) for c in cols]
    out = []
    for row in ws.iter_rows(min_row=2, values_only=True):
        if row[0] is None:
            continue
        out.append({c: _value(row[i]) for c, i in zip(cols, idx, strict=True)})
    return out


def main(src: str, dst: str) -> None:
    wb = openpyxl.load_workbook(src, data_only=True)
    trx = _rows(wb["Input TRX"], TRX_COLS)
    out_ws = wb["Output"]
    header = [c.value for c in out_ws[4]]
    first = header.index(NAMES[0])
    if header[first:first + 10] != NAMES:
        raise SystemExit(f"colunas inesperadas na aba Output: {header[first:first + 10]}")
    casos = []
    for row in out_ws.iter_rows(min_row=5, values_only=True):
        if row[header.index("N_ID_CUSTOMER")] is None:
            continue
        cliente = row[header.index("N_ID_CUSTOMER")]
        t0 = [t for t in trx if t["N_ID_CUSTOMER"] == cliente and t["ES_T0"] == 1]
        if len(t0) != 1:
            raise SystemExit(f"{cliente}: esperado 1 T0, achei {len(t0)}")
        esperado = {n: (None if v == "NULL" else v) for n, v in zip(NAMES, row[first:first + 10], strict=True)}
        casos.append({"caso": t0[0]["CASO"], "cliente": cliente, "id_trx": t0[0]["ID_TRX"],
                      "descricao": row[header.index(NAMES[-1]) + 1], "esperado": esperado})
    doc = {"fonte": Path(src).name, "variaveis": NAMES, "casos": casos, "trx": trx,
           "abr": _rows(wb["Input ABR"], ABR_COLS), "vu": _rows(wb["Input VU"], VU_COLS)}
    Path(dst).parent.mkdir(parents=True, exist_ok=True)
    Path(dst).write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(casos)} casos, {len(trx)} TRX, {len(doc['abr'])} ABR, {len(doc['vu'])} VU -> {dst}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
