import re
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest

from sentinela.db import plan, read_sql, schema
from sentinela.db.convert import amount, cents, naive_utc
from sentinela.domain.agg import EMPTY, WindowAgg
from sentinela.domain.windows import UTC, limits

SP = ZoneInfo("America/Sao_Paulo")
NOW = datetime(2026, 10, 29, 15, 7, 30, 123456, tzinfo=UTC)


def test_sql_comeca_pelo_prefixo_do_vsql():
    assert read_sql.SQL.startswith(read_sql.PREFIX)


def test_binds_casam_com_placeholders_e_tipos():
    placeholders = [int(n) for n in re.findall(r":(\d+)", read_sql.SQL)]
    b = read_sql.binds(42, limits(NOW, SP))
    assert placeholders == list(range(1, len(b) + 1))
    assert len(read_sql.INPUT_TYPES) == len(b)
    assert b[len(b) - 6] == 42 and b[len(b) - 3] == 42
    assert all(isinstance(x, datetime) and x.tzinfo is None for i, x in enumerate(b) if i not in (len(b) - 6, len(b) - 3))


def test_parse_soma_dia_corrente_nas_janelas_de_dias():
    row = [0, None, None, None] * read_sql.COLUMNS
    row = row[: read_sql.COLUMNS]
    row[0:4] = [Decimal(2), Decimal("3.00"), Decimal("1.00"), Decimal("2.00")]  # 1m
    hoje = 4 * 15
    row[hoje:hoje + 4] = [Decimal(1), Decimal("5.00"), Decimal("5.00"), Decimal("5.00")]
    d7 = hoje + 4
    row[d7:d7 + 4] = [Decimal(3), Decimal("9.00"), Decimal("0.50"), Decimal("4.00")]  # dias fechados de 7d
    v = read_sql.parse(row)
    assert v.aggs[0] == WindowAgg(2, 300, 100, 200)
    assert v.aggs[1] == EMPTY
    assert v.aggs[15] == WindowAgg(4, 1400, 50, 500)
    assert v.aggs[16] == WindowAgg(1, 500, 500, 500)  # 31d sem dia fechado = só hoje


def test_parse_rejeita_linha_errada():
    with pytest.raises(ValueError):
        read_sql.parse([0] * 3)


def test_conversoes():
    assert cents(Decimal("123.45")) == 12345
    assert amount(12345) == Decimal("123.45")
    assert naive_utc(NOW) == datetime(2026, 10, 29, 15, 7, 30, 123456)
    with pytest.raises(ValueError):
        cents(Decimal("1.234"))


def test_plano_esperado():
    ok = ["PARTITION RANGE ITERATOR", "INDEX RANGE SCAN | TX_ACC_TS_AMT_IX", "INDEX RANGE SCAN | DAILY_AGG_PK"]
    assert plan.problems(ok) == []
    assert len(plan.problems(ok + ["TABLE ACCESS FULL | TX"])) == 1
    assert len(plan.problems([])) == 3


def test_split_do_schema():
    stmts = schema.split("-- c\nCREATE TABLE a (\n x NUMBER\n);\n\nCREATE INDEX i ON a (x);\n")
    assert stmts == ["CREATE TABLE a (\n x NUMBER\n)", "CREATE INDEX i ON a (x)"]
    assert len(schema.split(schema.schema_path().read_text(encoding="utf-8"))) == 3
