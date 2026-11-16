"""Eventos das três fontes (TRX, ABR, VU), a compra avaliada (T0) e a classificação de cada TRX pelas regras do
Excel de validação (docs/specs/variaveis-xgboost.md, seção 3).

Instantes são hora local de FS_ZONE, sem fuso, ao segundo: é a escala em que o Excel mede as janelas.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, tzinfo
from decimal import Decimal

ORIGEN_BASE = "atmpos_trxs"
MTI_BASE = 200
PCODES_GLOBAL = frozenset({3000, 2000, 6000})
RESPONSE_APROBADA = "00"
ENTRY_PRESENTE = frozenset({9, 20, 21, 22, 23, 50, 51, 52, 53, 70, 71, 72, 73, 800, 801, 802, 803, 900, 901, 902,
                            903, 910, 911, 912, 913})
PAIS_NACIONAL = 152
NOMBRE_NACIONAL = "WEBPAY"
CPASS = frozenset({999901, 999903})


def local_second(t: datetime, zone: tzinfo) -> datetime:
    """Hora local sem fuso, arredondada ao segundo. Instante com fuso é convertido para zone; sem fuso, já é local."""
    if t.tzinfo is not None:
        t = t.astimezone(zone).replace(tzinfo=None)
    if t.microsecond >= 500_000:
        t += timedelta(seconds=1)
    return t.replace(microsecond=0)


def franja(hora: int) -> str:
    """M madrugada (00–05), D diurno (06–14), T tarde-noche (15–23)."""
    return "T" if hora > 14 else "D" if hora > 5 else "M"


@dataclass(frozen=True, slots=True)
class TrxFlags:
    base: bool      # ORIGEN e MTI: só linhas base entram em alguma janela
    ap: bool        # aprovada global
    re: bool        # rechazada global (resposta nula conta como rechazada)
    ap_aus: bool    # aprovada com tarjeta ausente (V7)
    re_npd: bool    # rechazada, nacional, presente, diurna (V8)
    ap_mad: bool    # aprovada de madrugada (V9)


@dataclass(frozen=True, slots=True)
class Trx:
    id_trx: str
    cliente: str
    ts: datetime
    fecha_informacion: datetime | None = None
    monto: Decimal | None = None
    response_code: str | None = None
    pcode: int | None = None
    entry_mode: int | None = None
    pais: int | None = None
    nombre_comercio: str | None = None
    mcc: int | None = None
    comercio: str | None = None
    origen: str | None = None
    mti: int | None = None
    flags: TrxFlags = field(init=False, compare=False)

    def __post_init__(self) -> None:
        if not self.id_trx or not self.cliente:
            raise ValueError("ID_TRX e N_ID_CUSTOMER são obrigatórios")
        if self.ts.tzinfo is not None or self.ts.microsecond:
            raise ValueError(f"ts precisa ser hora local ao segundo: {self.ts}")
        base = self.origen == ORIGEN_BASE and self.mti == MTI_BASE
        glob = base and self.pcode in PCODES_GLOBAL
        ap = glob and self.response_code == RESPONSE_APROBADA
        re = glob and self.response_code != RESPONSE_APROBADA
        presente = self.entry_mode in ENTRY_PRESENTE
        nacional = self.pais == PAIS_NACIONAL or (self.nombre_comercio is not None
                                                   and NOMBRE_NACIONAL in self.nombre_comercio)
        f = franja(self.ts.hour)
        object.__setattr__(self, "flags", TrxFlags(base, ap, re, ap and not presente,
                                                   re and nacional and presente and f == "D", ap and f == "M"))


ABR_ATTRS = ("lenguage", "color", "time_zone", "device_model", "device_name", "device_maker", "gps_compatible",
             "version_os", "device_id", "random_seed")


@dataclass(frozen=True, slots=True)
class Abr:
    cliente: str
    ts: datetime
    fecha_informacion: datetime | None = None
    lenguage: str | None = None
    color: str | None = None
    time_zone: str | None = None
    device_model: str | None = None
    device_name: str | None = None
    device_maker: str | None = None
    gps_compatible: str | None = None
    version_os: str | None = None
    device_id: str | None = None
    random_seed: str | None = None
    id_evento: str | None = None

    @property
    def valido(self) -> bool:
        """Excel: COUNTA(E:N) > 0, algum atributo do dispositivo preenchido."""
        return any(getattr(self, a) not in (None, "") for a in ABR_ATTRS)


@dataclass(frozen=True, slots=True)
class Vu:
    cliente: str
    ts: datetime
    fecha_informacion: datetime | None = None
    ip: str | None = None
    canal: str | None = None
    tipo: int | None = None
    id_evento: str | None = None

    @property
    def cpass(self) -> bool:
        return self.tipo in CPASS


@dataclass(frozen=True, slots=True)
class T0:
    """A compra avaliada. id_trx é informativo (None quando o consumidor não o envia)."""

    cliente: str
    ts: datetime
    monto: Decimal | None = None
    mcc: int | None = None
    comercio: str | None = None
    id_trx: str | None = None

    @staticmethod
    def of(t: Trx) -> T0:
        return T0(t.cliente, t.ts, t.monto, t.mcc, t.comercio, t.id_trx)
