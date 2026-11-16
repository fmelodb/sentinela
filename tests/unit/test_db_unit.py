"""Partes do pacote db que não precisam do banco: SQL gerado, binds, parse da linha, schema e plano."""

from datetime import datetime
from decimal import Decimal

from sentinela.db import plan, read_sql, rows, schema
from sentinela.domain.events import T0
from sentinela.domain.variables import FILTERS, WINDOWS


def test_sql_tem_as_colunas_e_os_binds_declarados():
    assert read_sql.SQL.startswith(read_sql.PREFIX)
    assert read_sql.COLUMNS == 6 + 4 * 10 + 4
    names = {p[1:].split()[0].rstrip(",)") for p in read_sql.SQL.split() if p.startswith(":")}
    assert names == set(read_sql.INPUT_TYPES)


def test_binds_janelas_em_segundos():
    b = read_sql.binds(T0("C", datetime(2026, 9, 30, 12), Decimal(1), None, None))
    assert b["hi"] == datetime(2026, 9, 30, 11, 59, 59)
    assert b["lo92"] == datetime(2026, 6, 30, 12)
    assert b["lo5"] == datetime(2026, 9, 30, 11, 55)
    assert b["mcc"] is None and b["com"] is None


def test_parse():
    row = list(range(1, 7))
    for _ in FILTERS:
        row += [3, 1, Decimal("10.50"), None]
    row += [1, 2, 3, 4]
    a = read_sql.parse(row)
    assert a.n == dict(zip(WINDOWS, range(1, 7), strict=True))
    assert a.f["f8"].cnt == 3 and a.f["f8"].nnull == 1 and a.f["f8"].sum == Decimal("10.50") and a.f["f8"].max is None
    assert (a.abr_1h, a.abr_92, a.vu_92, a.cpass_92) == (1, 2, 3, 4)


def test_inserts_com_tipos_para_cada_coluna():
    for k in (rows.TRX, rows.ABR, rows.VU):
        assert k.sql.count(":") == len(k.types)


def test_schema_divide_comandos():
    stmts = schema.split(schema.schema_path().read_text(encoding="utf-8"))
    assert sum(s.startswith("CREATE TABLE") for s in stmts) == 3
    assert all(not s.rstrip().endswith(";") for s in stmts)


def test_plano():
    ok = ["INDEX RANGE SCAN | TRX_CLI_TS_IX", "INDEX RANGE SCAN | ABR_CLI_TS_IX", "INDEX RANGE SCAN | VU_CLI_TS_IX"]
    assert plan.problems(ok) == []
    assert plan.problems(ok[:2] + ["TABLE ACCESS FULL | VU"]) == ["índice VU_CLI_TS_IX não usado",
                                                                   "TABLE ACCESS no plano: o índice coberto não foi "
                                                                   "suficiente"]
