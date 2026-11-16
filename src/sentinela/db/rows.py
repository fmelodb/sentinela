"""INSERTs das três fontes e a conversão dos eventos do domínio em binds. Compartilhado pelo store da API e pela
carga em massa."""

from __future__ import annotations

from dataclasses import dataclass

import oracledb

from sentinela.domain.events import ABR_ATTRS, Abr, Trx, Vu

ORA_UNIQUE_CONSTRAINT = 1
_D, _N = oracledb.DB_TYPE_DATE, oracledb.DB_TYPE_NUMBER

TRX_COLS = ("id_trx", "cliente", "ts", "fecha_informacion", "monto", "response_code", "pcode", "entry_mode", "pais",
            "nombre_comercio", "mcc", "comercio", "origen", "mti", "base", "ap", "re", "ap_aus", "re_npd", "ap_mad")
# Strings com tamanho: no executemany o driver dimensiona o buffer pela primeira linha se não souber o máximo.
TRX_TYPES = (64, 64, _D, _D, _N, 8, _N, _N, _N, 200, _N, 64, 64, _N, _N, _N, _N, _N, _N, _N)
ABR_COLS = ("cliente", "ts", "fecha_informacion", *ABR_ATTRS, "valido", "id_evento")
ABR_TYPES = (64, _D, _D, *(128,) * len(ABR_ATTRS), _N, 64)
VU_COLS = ("cliente", "ts", "fecha_informacion", "ip", "canal", "tipo", "cpass", "id_evento")
VU_TYPES = (64, _D, _D, 64, 64, _N, _N, 64)


def _insert(table: str, cols: tuple[str, ...]) -> str:
    return f"INSERT INTO {table} ({', '.join(cols)}) VALUES ({', '.join(f':{i + 1}' for i in range(len(cols)))})"


INSERT_TRX = _insert("trx", TRX_COLS)
INSERT_ABR = _insert("abr", ABR_COLS)
INSERT_VU = _insert("vu", VU_COLS)


def trx_binds(t: Trx) -> tuple:
    f = t.flags
    return (t.id_trx, t.cliente, t.ts, t.fecha_informacion, t.monto, t.response_code, t.pcode, t.entry_mode, t.pais,
            t.nombre_comercio, t.mcc, t.comercio, t.origen, t.mti,
            int(f.base), int(f.ap), int(f.re), int(f.ap_aus), int(f.re_npd), int(f.ap_mad))


def abr_binds(a: Abr) -> tuple:
    return (a.cliente, a.ts, a.fecha_informacion, *(getattr(a, c) for c in ABR_ATTRS), int(a.valido), a.id_evento)


def vu_binds(v: Vu) -> tuple:
    return (v.cliente, v.ts, v.fecha_informacion, v.ip, v.canal, v.tipo, int(v.cpass), v.id_evento)


@dataclass(frozen=True, slots=True)
class Kind:
    table: str
    sql: str
    types: tuple
    binds: object  # Callable[[evento], tuple]


TRX = Kind("trx", INSERT_TRX, TRX_TYPES, trx_binds)
ABR = Kind("abr", INSERT_ABR, ABR_TYPES, abr_binds)
VU = Kind("vu", INSERT_VU, VU_TYPES, vu_binds)
TABLES = ("trx", "abr", "vu")


def is_duplicate(e: oracledb.Error) -> bool:
    err = e.args[0] if e.args else None
    return getattr(err, "code", None) == ORA_UNIQUE_CONSTRAINT


@dataclass(frozen=True)
class LoteResult:
    recebidas: int
    novas: int
    duplicadas: int
    erros: list[dict]

    def to_json(self) -> dict:
        return {"recebidas": self.recebidas, "novas": self.novas, "duplicadas": self.duplicadas, "erros": self.erros}


def lote_result(n: int, batch_errors: list) -> LoteResult:
    dup = sum(1 for e in batch_errors if e.code == ORA_UNIQUE_CONSTRAINT)
    others = [{"indice": e.offset, "erro": e.message.splitlines()[0]} for e in batch_errors
              if e.code != ORA_UNIQUE_CONSTRAINT]
    return LoteResult(n, n - len(batch_errors), dup, others)
