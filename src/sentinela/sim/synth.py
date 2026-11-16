"""Gerador sintético das três fontes (TRX, ABR, VU), usado pelo backfill (direto no banco) e pelo gerador (NDJSON
com os nomes de coluna da fonte, o mesmo formato que a API e o `importar` aceitam).

Cada cliente tem um perfil fixo (três MCCs e três comercios favoritos, um ou dois dispositivos), para que os
filtros "mesmo MCC" e "mesmo comercio" das variáveis tenham acertos. A mistura de categorias exercita todas as
regras do Excel: ~90% aprovadas, resposta nula, tarjeta presente/ausente, país 152, WEBPAY, ORIGEN/MTI fora da
base, PCODE não global, monto nulo e zero, ABR sem atributos e no mesmo segundo, VU com ~10% de CPASS.

Instantes são hora local de FS_ZONE, sem fuso, ao segundo (como o domínio).
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path

import numpy as np
import orjson

from sentinela.domain.events import Abr, Trx, Vu
from sentinela.sim.samplers import np_clients, np_montos, np_rng

MCCS = (5411, 5812, 5541, 5912, 5311, 5732, 4121, 5999, 7011, 4814, 5651, 5814, 5964, 4900, 5200, 5944)
COMERCIOS = 20_000
_RESP = (("00", "05", "51", None), (0.90, 0.04, 0.04, 0.02))
_PCODE = ((3000, 2000, 6000, 1000), (0.70, 0.10, 0.10, 0.10))
_ENTRY = ((9, 900, 51, 71, 801, 910, 1, 10, 81, None), (0.25, 0.15, 0.08, 0.06, 0.04, 0.02, 0.15, 0.10, 0.13, 0.02))
_PAIS = ((152, 840, None), (0.85, 0.10, 0.05))
_NOMBRE = (("COMERCIO", "WEBPAY.CL", None), (0.83, 0.15, 0.02))
_ORIGEN = (("atmpos_trxs", "otro"), (0.97, 0.03))
_MTI = ((200, 210), (0.98, 0.02))
_CANAL = (("APP", "WEB"), (0.7, 0.3))
_TIPO = ((999901, 999903, 999910, 999920), (0.05, 0.05, 0.45, 0.45))
_MAKERS = ("SAMSUNG", "APPLE", "XIAOMI", "MOTOROLA", "HUAWEI")


def cliente_id(n: int) -> str:
    return f"C{n}"


def mcc_of(cli: int, k: int) -> int:
    return MCCS[(cli * 7 + k) % len(MCCS)]


def comercio_of(cli: int, k: int) -> str:
    return f"COM{(cli * 13 + k) % COMERCIOS:05d}"


def _pick(g: np.random.Generator, choices: tuple, n: int) -> list:
    values, p = choices
    return [values[i] for i in g.choice(len(values), size=n, p=p).tolist()]


def _midnight(t: datetime) -> datetime:
    return t.replace(hour=0, minute=0, second=0)


class Synth:
    def __init__(self, clients: int, distribution: str, seed: int, purpose: str):
        self.clients = clients
        self.distribution = distribution
        self.g = np_rng(seed, purpose)

    def _times(self, start: datetime, span_s: int, n: int) -> tuple[list[int], list[datetime]]:
        g = self.g
        cli = np_clients(g, n, self.clients, self.distribution).tolist()
        off = g.integers(0, max(1, span_s), size=n, dtype=np.int64).tolist()
        return cli, [start + timedelta(seconds=o) for o in off]

    def trx(self, start: datetime, span_s: int, n: int, first_id: int, prefix: str) -> list[Trx]:
        g = self.g
        cli, ts = self._times(start, span_s, n)
        montos = np_montos(g, n).tolist()
        kind = g.random(n).tolist()  # < 0.01 monto nulo; < 0.015 monto zero
        fav = g.integers(0, 3, size=n).tolist()
        other = g.random(n).tolist()  # < 0.1 MCC/comercio fora dos favoritos; < 0.13 nulo
        any_mcc = g.integers(0, len(MCCS), size=n).tolist()
        resp, pcode, entry = _pick(g, _RESP, n), _pick(g, _PCODE, n), _pick(g, _ENTRY, n)
        pais, nombre = _pick(g, _PAIS, n), _pick(g, _NOMBRE, n)
        origen, mti = _pick(g, _ORIGEN, n), _pick(g, _MTI, n)
        out = []
        for i in range(n):
            c, k, o = cli[i], fav[i], other[i]
            m = None if kind[i] < 0.01 else Decimal(0) if kind[i] < 0.015 else Decimal(montos[i])
            if o < 0.10:
                mcc, com = MCCS[any_mcc[i]], comercio_of(c * 31 + any_mcc[i], 0)
            elif o < 0.13:
                mcc, com = None, None
            else:
                mcc, com = mcc_of(c, k), comercio_of(c, k)
            nom = None if nombre[i] is None else f"{nombre[i]} {com or 'X'}"
            out.append(Trx(f"{prefix}{first_id + i}", cliente_id(c), ts[i], _midnight(ts[i]), m, resp[i], pcode[i],
                           entry[i], pais[i], nom, mcc, com, origen[i], mti[i]))
        return out

    def abr(self, start: datetime, span_s: int, n: int, first_id: int, prefix: str | None) -> list[Abr]:
        """n notificações; ~5% sem atributos (inválidas) e ~10% repetidas no mesmo segundo por outro dispositivo."""
        g = self.g
        cli, ts = self._times(start, span_s, n)
        dev = (g.random(n) < 0.2).astype(np.int64).tolist()
        r = g.random(n).tolist()
        out: list[Abr] = []
        for i in range(n):
            if len(out) >= n:
                break
            c = cli[i]
            ev = None if prefix is None else f"{prefix}{first_id + len(out)}"
            if r[i] < 0.05:
                out.append(Abr(cliente_id(c), ts[i], _midnight(ts[i]), id_evento=ev))
                continue
            out.append(self._device(c, dev[i], ts[i], ev))
            if r[i] > 0.90 and len(out) < n:
                ev2 = None if prefix is None else f"{prefix}{first_id + len(out)}"
                out.append(self._device(c, 1 - dev[i], ts[i], ev2))
        return out

    @staticmethod
    def _device(c: int, k: int, ts: datetime, ev: str | None) -> Abr:
        d = c * 2 + k
        return Abr(cliente_id(c), ts, _midnight(ts), "es", "24", "-3", f"MODEL_{d % 40}", f"NAME_{d % 97}",
                   _MAKERS[d % len(_MAKERS)], "1", str(12 + d % 5), f"DEV{c}-{k}", None, ev)

    def vu(self, start: datetime, span_s: int, n: int, first_id: int, prefix: str | None) -> list[Vu]:
        g = self.g
        cli, ts = self._times(start, span_s, n)
        canal, tipo = _pick(g, _CANAL, n), _pick(g, _TIPO, n)
        return [Vu(cliente_id(c), ts[i], _midnight(ts[i]), f"10.{c % 256}.{(c // 256) % 256}.{i % 4}", canal[i],
                   tipo[i], None if prefix is None else f"{prefix}{first_id + i}") for i, c in enumerate(cli)]


# ------------------------------------------------------------------ formato da fonte (API, NDJSON, importar)

def _iso(t: datetime | None) -> str | None:
    return None if t is None else t.isoformat()


def trx_source(t: Trx) -> dict:
    return {"ID_TRX": t.id_trx, "N_ID_CUSTOMER": t.cliente, "D_TRANSMISSION_DATE_AND_TIME": _iso(t.ts),
            "FECHA_INFORMACION": _iso(t.fecha_informacion),
            "F_AMOUNT_TRANSACTION": None if t.monto is None else int(t.monto) if t.monto == int(t.monto)
            else str(t.monto),
            "S_RESPONSE_CODE": t.response_code, "N_PCODE": t.pcode, "N_ENTRY_MODE": t.entry_mode,
            "N_ACQUIRING_INSTITUTION_COUNTRY_CODE": t.pais, "S_CARD_ACCEPTOR_NAME": t.nombre_comercio,
            "N_MERCHANT_TYPE": t.mcc, "S_CARD_ACCEPT_EXT_ID_CODE": t.comercio, "ORIGEN": t.origen, "N_MTI": t.mti}


def abr_source(a: Abr) -> dict:
    return {"N_ID_CUSTOMER": a.cliente, "D_TRANSMISSION_DATE_AND_TIME": _iso(a.ts),
            "FECHA_INFORMACION": _iso(a.fecha_informacion), "S_LENGUAGE": a.lenguage, "N_COLOR": a.color,
            "N_TIME_ZONE": a.time_zone, "S_DEVICE_MODEL": a.device_model, "S_DEVICE_NAME": a.device_name,
            "S_DEVICE_MAKER": a.device_maker, "N_GPS_COMPATIBLE": a.gps_compatible, "S_VERSION_OS": a.version_os,
            "S_DEVICE_ID": a.device_id, "S_RANDOM_SEED": a.random_seed, "ID_EVENTO": a.id_evento}


def vu_source(v: Vu) -> dict:
    return {"N_ID_CUSTOMER": v.cliente, "D_TRANSMISSION_DATE_AND_TIME": _iso(v.ts),
            "FECHA_INFORMACION": _iso(v.fecha_informacion), "S_IP_ADDRESS": v.ip, "S_CHANNEL_NAME": v.canal,
            "N_TRANSACTION_TYPE": v.tipo, "ID_EVENTO": v.id_evento}


SOURCE = {"trx": trx_source, "abr": abr_source, "vu": vu_source}
FONTES = ("trx", "abr", "vu")


def write_ndjson(path: Path, fonte: str, events: Iterable) -> int:
    to = SOURCE[fonte]
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with path.open("wb") as w:
        for e in events:
            w.write(orjson.dumps(to(e), option=orjson.OPT_APPEND_NEWLINE))
            n += 1
    return n


def dataset_path(directory: Path, fonte: str) -> Path:
    return directory / f"{fonte}.ndjson"


def generate(directory: Path, clients: int, distribution: str, seed: int, counts: dict[str, int],
             end: datetime, days: int) -> dict[str, int]:
    """Um NDJSON por fonte, com eventos espalhados nos `days` dias até `end` (hora local), em ordem de instante.
    IDs: ID_TRX G<n>; ID_EVENTO GA<n> e GV<n> (a ingestão de ABR e VU fica idempotente)."""
    span = days * 86_400
    start = end - timedelta(seconds=span)
    s = Synth(clients, distribution, seed, "gerador")
    made: dict[str, Sequence] = {
        "trx": s.trx(start, span, counts.get("trx", 0), 1, "G"),
        "abr": s.abr(start, span, counts.get("abr", 0), 1, "GA"),
        "vu": s.vu(start, span, counts.get("vu", 0), 1, "GV"),
    }
    out = {}
    for fonte, events in made.items():
        if counts.get(fonte, 0) > 0:
            out[fonte] = write_ndjson(dataset_path(directory, fonte), fonte, sorted(events, key=lambda e: e.ts))
    return out
