-- Schema da POC das 10 variáveis do XGBoost (docs/specs/variaveis-xgboost.md). Idempotente.
-- ts é a hora local de FS_ZONE ao segundo (DATE): a escala em que o Excel mede as janelas.
-- Partições diárias para a retenção; índices globais cobertos para a leitura fazer uma só varredura por cliente
-- nos 92 dias, em vez de uma sondagem por partição.

CREATE TABLE IF NOT EXISTS trx (
  id_trx           VARCHAR2(64)   NOT NULL,  -- ID_TRX
  cliente          VARCHAR2(64)   NOT NULL,  -- N_ID_CUSTOMER
  ts               DATE           NOT NULL,  -- D_TRANSMISSION_DATE_AND_TIME
  fecha_informacion DATE,                    -- FECHA_INFORMACION
  monto            NUMBER(18,2),             -- F_AMOUNT_TRANSACTION
  response_code    VARCHAR2(8),              -- S_RESPONSE_CODE
  pcode            NUMBER(10),               -- N_PCODE
  entry_mode       NUMBER(10),               -- N_ENTRY_MODE
  pais             NUMBER(10),               -- N_ACQUIRING_INSTITUTION_COUNTRY_CODE
  nombre_comercio  VARCHAR2(200),            -- S_CARD_ACCEPTOR_NAME
  mcc              NUMBER(10),               -- N_MERCHANT_TYPE
  comercio         VARCHAR2(64),             -- S_CARD_ACCEPT_EXT_ID_CODE
  origen           VARCHAR2(64),             -- ORIGEN
  mti              NUMBER(10),               -- N_MTI
  -- Classificação do Excel, calculada na ingestão (sentinela.domain.events.TrxFlags)
  base             NUMBER(1)      NOT NULL,
  ap               NUMBER(1)      NOT NULL,
  re               NUMBER(1)      NOT NULL,
  ap_aus           NUMBER(1)      NOT NULL,
  re_npd           NUMBER(1)      NOT NULL,
  ap_mad           NUMBER(1)      NOT NULL,
  CONSTRAINT trx_pk PRIMARY KEY (id_trx)
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (DATE '2000-01-01') );

CREATE INDEX IF NOT EXISTS trx_cli_ts_ix
  ON trx (cliente, ts, base, ap, re, ap_aus, re_npd, ap_mad, mcc, comercio, monto);

CREATE TABLE IF NOT EXISTS abr (
  cliente          VARCHAR2(64)   NOT NULL,
  ts               DATE           NOT NULL,
  fecha_informacion DATE,
  lenguage         VARCHAR2(128),
  color            VARCHAR2(128),
  time_zone        VARCHAR2(128),
  device_model     VARCHAR2(128),
  device_name      VARCHAR2(128),
  device_maker     VARCHAR2(128),
  gps_compatible   VARCHAR2(128),
  version_os       VARCHAR2(128),
  device_id        VARCHAR2(128),
  random_seed      VARCHAR2(128),
  valido           NUMBER(1)      NOT NULL,  -- algum atributo do dispositivo preenchido
  id_evento        VARCHAR2(64)              -- opcional; idempotência quando informado
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (DATE '2000-01-01') );

CREATE INDEX IF NOT EXISTS abr_cli_ts_ix ON abr (cliente, ts, valido);
CREATE UNIQUE INDEX IF NOT EXISTS abr_evento_ux ON abr (id_evento);

CREATE TABLE IF NOT EXISTS vu (
  cliente          VARCHAR2(64)   NOT NULL,
  ts               DATE           NOT NULL,
  fecha_informacion DATE,
  ip               VARCHAR2(64),             -- S_IP_ADDRESS
  canal            VARCHAR2(64),             -- S_CHANNEL_NAME
  tipo             NUMBER(10),               -- N_TRANSACTION_TYPE
  cpass            NUMBER(1)      NOT NULL,  -- tipo em (999901, 999903)
  id_evento        VARCHAR2(64)
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (DATE '2000-01-01') );

CREATE INDEX IF NOT EXISTS vu_cli_ts_ix ON vu (cliente, ts, cpass);
CREATE UNIQUE INDEX IF NOT EXISTS vu_evento_ux ON vu (id_evento);
