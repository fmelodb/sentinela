-- Usuário da POC no Autonomous Database (ATP). Rode como ADMIN, por exemplo no SQL do Database Actions,
-- trocando <senha> pela mesma senha de ORACLE_PASSWORD. No ATP o tablespace padrão é DATA.
CREATE USER IF NOT EXISTS sentinela IDENTIFIED BY "senha" DEFAULT TABLESPACE data QUOTA UNLIMITED ON data;
GRANT DB_DEVELOPER_ROLE TO sentinela;
-- V$SQL e DBMS_XPLAN.DISPLAY_CURSOR: conferência do plano de leitura no backfill.
GRANT SELECT_CATALOG_ROLE TO sentinela;
GRANT SELECT ON sys.v_$sql TO sentinela;
GRANT SELECT ON sys.v_$sql_plan TO sentinela;
GRANT SELECT ON sys.v_$sql_plan_statistics_all TO sentinela;
GRANT SELECT ON sys.v_$session TO sentinela;
