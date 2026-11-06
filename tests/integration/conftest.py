"""Testes contra o Oracle. São destrutivos (esvaziam TX e DAILY_AGG): só rodam se ORACLE_DSN apontar para o
banco local (host oracle ou localhost), ou com FS_IT_DESTRUCTIVE=1 definido explicitamente."""

import os

import pytest

from sentinela.config import Env

LOCAL_HOSTS = ("oracle", "localhost", "127.0.0.1", "sentinela-oracle")


def _allowed(dsn: str | None) -> bool:
    if dsn is None:
        return False
    host = dsn.split("/")[0].split(":")[0].lstrip("/").lower()
    return host in LOCAL_HOSTS or os.environ.get("FS_IT_DESTRUCTIVE") == "1"


def pytest_collection_modifyitems(config, items):
    env = Env.from_environ()
    reason = None
    if env.oracle_dsn is None or env.oracle_password is None:
        reason = "ORACLE_DSN/ORACLE_PASSWORD ausentes"
    elif not _allowed(env.oracle_dsn):
        reason = f"ORACLE_DSN {env.oracle_dsn} não é local; defina FS_IT_DESTRUCTIVE=1 para rodar"
    for item in items:
        if "integration" in str(item.fspath):
            item.add_marker(pytest.mark.integration)
            if reason:
                item.add_marker(pytest.mark.skip(reason=reason))


@pytest.fixture(scope="session")
def env() -> Env:
    return Env.from_environ()


@pytest.fixture
def conn(env):
    from sentinela.db.maintenance import Maintenance
    from sentinela.db.pool import connect

    c = connect(env)
    Maintenance(c, env.zone).reset()
    yield c
    c.close()


@pytest.fixture
def m(conn, env):
    from sentinela.db.maintenance import Maintenance

    return Maintenance(conn, env.zone)
