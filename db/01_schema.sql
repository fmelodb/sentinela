-- Schema da POC. Idempotente: pode ser executado de novo sem erro.
CREATE TABLE IF NOT EXISTS tx (
  tx_id       NUMBER(19)    NOT NULL,
  account_id  NUMBER(10)    NOT NULL,
  ts          TIMESTAMP(6)  NOT NULL,
  amount      NUMBER(14,2)  NOT NULL,
  CONSTRAINT tx_pk PRIMARY KEY (tx_id)
)
PARTITION BY RANGE (ts) INTERVAL (NUMTODSINTERVAL(1, 'DAY'))
( PARTITION p_inicial VALUES LESS THAN (TIMESTAMP '2000-01-01 00:00:00') );

CREATE INDEX IF NOT EXISTS tx_acc_ts_amt_ix ON tx (account_id, ts, amount) LOCAL;

CREATE TABLE IF NOT EXISTS daily_agg (
  account_id  NUMBER(10)    NOT NULL,
  dia         DATE          NOT NULL,
  cnt         NUMBER(10)    NOT NULL,
  sum_amount  NUMBER(18,2)  NOT NULL,
  min_amount  NUMBER(14,2)  NOT NULL,
  max_amount  NUMBER(14,2)  NOT NULL,
  CONSTRAINT daily_agg_pk PRIMARY KEY (account_id, dia)
)
ORGANIZATION INDEX;
