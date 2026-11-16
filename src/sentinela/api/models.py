"""Corpos da API com os nomes de coluna das fontes (TRANSACCION_FINANCIERA, NOTIFICACION_ABR, NOTIFICACION_VU),
para os dados originais entrarem sem tradução. Colunas extras (CASO, ES_T0, COMENTARIO_CASO...) são ignoradas.

Instante com fuso é convertido para FS_ZONE; sem fuso, é tomado como hora local de FS_ZONE.
"""

from __future__ import annotations

from datetime import datetime, tzinfo
from decimal import Decimal
from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from sentinela.domain.events import Abr, T0, Trx, Vu, local_second

Id = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=64)]
Text = Annotated[str, StringConstraints(max_length=200)]
Short = Annotated[str, StringConstraints(max_length=128)]
Code = Annotated[str, StringConstraints(max_length=64)]
Amount = Annotated[Decimal, Field(max_digits=18, decimal_places=2)]
MAX_LOTE = 10_000


class _Source(BaseModel):
    model_config = ConfigDict(extra="ignore", coerce_numbers_to_str=True, frozen=True)


def _local(t: datetime | None, zone: tzinfo) -> datetime | None:
    return None if t is None else local_second(t, zone)


def _blank(s: str | None) -> str | None:
    return None if s is None or s == "" else s


class TrxIn(_Source):
    ID_TRX: Id
    N_ID_CUSTOMER: Id
    D_TRANSMISSION_DATE_AND_TIME: datetime
    FECHA_INFORMACION: datetime | None = None
    F_AMOUNT_TRANSACTION: Amount | None = None
    S_RESPONSE_CODE: Annotated[str, StringConstraints(max_length=8)] | None = None
    N_PCODE: int | None = None
    N_ENTRY_MODE: int | None = None
    N_ACQUIRING_INSTITUTION_COUNTRY_CODE: int | None = None
    S_CARD_ACCEPTOR_NAME: Text | None = None
    N_MERCHANT_TYPE: int | None = None
    S_CARD_ACCEPT_EXT_ID_CODE: Code | None = None
    ORIGEN: Code | None = None
    N_MTI: int | None = None

    def to_domain(self, zone: tzinfo) -> Trx:
        return Trx(self.ID_TRX, self.N_ID_CUSTOMER, local_second(self.D_TRANSMISSION_DATE_AND_TIME, zone),
                   _local(self.FECHA_INFORMACION, zone), self.F_AMOUNT_TRANSACTION, _blank(self.S_RESPONSE_CODE),
                   self.N_PCODE, self.N_ENTRY_MODE, self.N_ACQUIRING_INSTITUTION_COUNTRY_CODE,
                   _blank(self.S_CARD_ACCEPTOR_NAME), self.N_MERCHANT_TYPE, _blank(self.S_CARD_ACCEPT_EXT_ID_CODE),
                   _blank(self.ORIGEN), self.N_MTI)


class AbrIn(_Source):
    N_ID_CUSTOMER: Id
    D_TRANSMISSION_DATE_AND_TIME: datetime
    FECHA_INFORMACION: datetime | None = None
    S_LENGUAGE: Short | None = None
    N_COLOR: Short | None = None
    N_TIME_ZONE: Short | None = None
    S_DEVICE_MODEL: Short | None = None
    S_DEVICE_NAME: Short | None = None
    S_DEVICE_MAKER: Short | None = None
    N_GPS_COMPATIBLE: Short | None = None
    S_VERSION_OS: Short | None = None
    S_DEVICE_ID: Short | None = None
    S_RANDOM_SEED: Short | None = None
    ID_EVENTO: Id | None = Field(None, description="opcional; se enviado, a ingestão é idempotente por ele")

    def to_domain(self, zone: tzinfo) -> Abr:
        return Abr(self.N_ID_CUSTOMER, local_second(self.D_TRANSMISSION_DATE_AND_TIME, zone),
                   _local(self.FECHA_INFORMACION, zone), _blank(self.S_LENGUAGE), _blank(self.N_COLOR),
                   _blank(self.N_TIME_ZONE), _blank(self.S_DEVICE_MODEL), _blank(self.S_DEVICE_NAME),
                   _blank(self.S_DEVICE_MAKER), _blank(self.N_GPS_COMPATIBLE), _blank(self.S_VERSION_OS),
                   _blank(self.S_DEVICE_ID), _blank(self.S_RANDOM_SEED), self.ID_EVENTO)


class VuIn(_Source):
    N_ID_CUSTOMER: Id
    D_TRANSMISSION_DATE_AND_TIME: datetime
    FECHA_INFORMACION: datetime | None = None
    S_IP_ADDRESS: Code | None = None
    S_CHANNEL_NAME: Code | None = None
    N_TRANSACTION_TYPE: int | None = None
    ID_EVENTO: Id | None = Field(None, description="opcional; se enviado, a ingestão é idempotente por ele")

    def to_domain(self, zone: tzinfo) -> Vu:
        return Vu(self.N_ID_CUSTOMER, local_second(self.D_TRANSMISSION_DATE_AND_TIME, zone),
                  _local(self.FECHA_INFORMACION, zone), _blank(self.S_IP_ADDRESS), _blank(self.S_CHANNEL_NAME),
                  self.N_TRANSACTION_TYPE, self.ID_EVENTO)


class T0In(_Source):
    """A compra avaliada. Aceita o mesmo corpo de TrxIn; só estes campos importam."""

    N_ID_CUSTOMER: Id
    D_TRANSMISSION_DATE_AND_TIME: datetime
    F_AMOUNT_TRANSACTION: Amount | None = None
    N_MERCHANT_TYPE: int | None = None
    S_CARD_ACCEPT_EXT_ID_CODE: Code | None = None
    ID_TRX: Id | None = None

    def to_domain(self, zone: tzinfo) -> T0:
        return T0(self.N_ID_CUSTOMER, local_second(self.D_TRANSMISSION_DATE_AND_TIME, zone),
                  self.F_AMOUNT_TRANSACTION, self.N_MERCHANT_TYPE, _blank(self.S_CARD_ACCEPT_EXT_ID_CODE),
                  self.ID_TRX)
