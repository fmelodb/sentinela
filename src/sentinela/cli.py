"""sentinela <programa> [argumentos]. Códigos de saída: 0 ok, 1 verificação falhou, 2 uso, 3 store indisponível,
4 rodada inválida, 5 erro inesperado.

No container da API (falam com o Oracle):  api, schema, backfill, job-diario, equivalencia
No container do simulador (só HTTP):        gerador, ingestor, leitor, carga, relatorio
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path

from sentinela import errors
from sentinela.config import Env
from sentinela.errors import UsageError


def exit_code_of(e: BaseException) -> int:
    if isinstance(e, errors.SentinelaError):
        return e.exit_code
    try:
        import oracledb

        if isinstance(e, oracledb.OperationalError | oracledb.InterfaceError):
            return errors.STORE_UNAVAILABLE
    except ImportError:
        pass
    return errors.UNEXPECTED


def _positive(s: str) -> int:
    try:
        v = int(s)
    except ValueError:
        raise argparse.ArgumentTypeError(f"precisa ser inteiro: {s}") from None
    if v <= 0:
        raise argparse.ArgumentTypeError(f"precisa ser positivo: {s}")
    return v


def _non_negative(s: str) -> int:
    v = int(s)
    if v < 0:
        raise argparse.ArgumentTypeError(f"não pode ser negativo: {s}")
    return v


def _now(s: str | None) -> datetime:
    from sentinela.domain.windows import UTC

    if s is None:
        return datetime.now(UTC)
    try:
        t = datetime.fromisoformat(s)
    except ValueError:
        raise UsageError(f"--now precisa ser ISO-8601, como 2026-10-29T15:07:30Z: {s}") from None
    if t.tzinfo is None:
        raise UsageError(f"--now precisa de fuso (Z ou ±hh:mm): {s}")
    return t.astimezone(UTC)


# ---------------------------------------------------------------- programas que falam com o Oracle

def cmd_api(a: argparse.Namespace) -> int:
    import uvicorn

    env = Env.from_environ()
    env.require_oracle()
    uvicorn.run("sentinela.api.app:app", host=a.host, port=a.port, workers=a.workers or env.api_workers,
                loop="uvloop", http="httptools", access_log=False, log_level="warning")
    return errors.OK


def cmd_schema(a: argparse.Namespace) -> int:
    from sentinela.db import schema
    from sentinela.db.pool import connect

    path = Path(a.arquivo) if a.arquivo else schema.schema_path()
    if not path.exists():
        raise UsageError(f"script de schema não encontrado: {path}")
    with connect(Env.from_environ()) as conn:
        schema.apply(conn, path)
    return errors.OK


def cmd_backfill(a: argparse.Namespace) -> int:
    from sentinela import backfill
    from sentinela.db.pool import connect
    from sentinela.sim.samplers import check_distribution

    if not backfill.RECENT_DAYS <= a.days <= backfill.MAX_DAYS:
        raise UsageError(f"--days precisa estar entre {backfill.RECENT_DAYS} e {backfill.MAX_DAYS}: {a.days}")
    check_distribution(a.distribution)
    env = Env.from_environ()
    with connect(env) as conn:
        backfill.run(conn, env.zone, a.accounts, a.days, a.seed, a.distribution, a.tx_per_day, _now(None))
    return errors.OK


def parse_day(s: str | None, today: date) -> date:
    if s is None:
        return today - timedelta(days=1)
    try:
        d = date.fromisoformat(s)
    except ValueError:
        raise UsageError(f"--day precisa ser YYYY-MM-DD: {s}") from None
    if d >= today:
        raise UsageError(f"--day precisa ser um dia fechado, anterior a hoje: {s}")
    return d


def cmd_job(a: argparse.Namespace) -> int:
    from sentinela.db.maintenance import Maintenance
    from sentinela.db.pool import connect
    from sentinela.domain.windows import civil_day

    env = Env.from_environ()
    today = civil_day(_now(None), env.zone)
    day = parse_day(a.day, today)
    with connect(env) as conn:
        m = Maintenance(conn, env.zone)
        print(f"job-diario: DAILY_AGG de {day} com {m.close_day(day)} contas", file=sys.stderr)
        dropped = m.drop_old_partitions(today)
        print(f"job-diario: partições removidas: {dropped or 'nenhuma'}", file=sys.stderr)
    return errors.OK


def cmd_equivalencia(a: argparse.Namespace) -> int:
    from sentinela.db.maintenance import Maintenance
    from sentinela.db.pool import connect
    from sentinela.verification import check

    if a.accounts < 1000:
        print(f"aviso: o critério de aceitação exige pelo menos 1.000 contas; rodando com {a.accounts}",
              file=sys.stderr)
    now = _now(a.now)
    env = Env.from_environ()
    api_url = (a.api_url or "http://localhost:8000").rstrip("/")
    with connect(env) as conn:
        results = asyncio.run(check.run(Maintenance(conn, env.zone), api_url, env.zone, a.accounts, a.seed, now,
                                        a.reset))
    print(f"equivalencia em {now.isoformat()} ({env.zone}), {a.accounts} contas, seed {a.seed}, API {api_url}")
    for r in results:
        print(("PASS  " if r.passed else "FAIL  ") + r.name)
        for d in r.details:
            print("      " + d)
    return errors.OK if all(r.passed for r in results) else errors.VERIFICATION_FAILED


# ---------------------------------------------------------------- simulador

def cmd_gerador(a: argparse.Namespace) -> int:
    from sentinela.sim import dataset
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    dataset.generate(Path(a.dataset), a.accounts, a.count, a.seed, a.distribution)
    print(f"gerador: {a.count} transações, {a.accounts} contas ({a.distribution}), seed {a.seed} -> {a.dataset}",
          file=sys.stderr)
    return errors.OK


def _load_args(a: argparse.Namespace, run: str, log: str | None, tps: int | None = None) -> object:
    from sentinela.sim.load import LoadArgs

    return LoadArgs(api_url=(a.api_url or Env.from_environ().api_url).rstrip("/"), tps=tps or a.tps,
                    duration=a.duration, warmup=a.warmup, concurrency=a.concurrency, run=run,
                    log=Path(log) if log else None)


def cmd_ingestor(a: argparse.Namespace) -> int:
    from sentinela.sim import load

    return load.run_ingestor(_load_args(a, a.run or load.default_run(), a.log), Path(a.dataset))


def cmd_leitor(a: argparse.Namespace) -> int:
    from sentinela.sim import load
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    return load.run_leitor(_load_args(a, a.run or load.default_run(), a.log), a.accounts, a.seed, a.distribution)


def cmd_carga(a: argparse.Namespace) -> int:
    from sentinela.sim import load
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    run = a.run or load.default_run()
    return load.carga(_load_args(a, run, None, a.tps_ingest), _load_args(a, run, None, a.tps_read),
                      Path(a.dataset), a.accounts, a.seed, a.distribution)


def cmd_relatorio(a: argparse.Namespace) -> int:
    from sentinela.report import build, write

    d = Path(a.diretorio)
    if not d.is_dir():
        raise UsageError(f"diretório não encontrado: {d}")
    files = build.load(d)
    stats = build.build(files)
    out = write.write_all(d, stats, build.series(files))
    print(f"relatorio: {len(files)} arquivo(s), {len(stats)} grupo(s) -> {out}", file=sys.stderr)
    return errors.OK


# ---------------------------------------------------------------- argumentos

def _load_opts(p: argparse.ArgumentParser, tps: bool = True) -> None:
    if tps:
        p.add_argument("--tps", type=_positive, default=600)
    p.add_argument("--duration", type=_positive, default=300, help="segundos (default 300)")
    p.add_argument("--warmup", type=_non_negative, default=30, help="segundos descartados (default 30)")
    p.add_argument("--concurrency", type=_positive, default=64, help="requisições em voo (default 64)")
    p.add_argument("--run", help="nome da execução (default: data/hora UTC)")
    p.add_argument("--api-url", help="default: $API_URL")


def _reader_opts(p: argparse.ArgumentParser) -> None:
    p.add_argument("--accounts", type=_positive, default=100_000)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--distribution", default="skewed", help="skewed ou uniform")


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="sentinela", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True, metavar="programa")

    p = sub.add_parser("api", help="sobe a ingest API e a query API (uvicorn)")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=_positive, default=8000)
    p.add_argument("--workers", type=_positive, help="default: $API_WORKERS")
    p.set_defaults(fn=cmd_api)

    p = sub.add_parser("schema", help="cria TX e DAILY_AGG (idempotente)")
    p.add_argument("--arquivo", help="default: $SENTINELA_SCHEMA ou db/01_schema.sql")
    p.set_defaults(fn=cmd_schema)

    p = sub.add_parser("backfill", help="destrutivo: popula 92 dias de histórico e confere o plano")
    p.add_argument("--accounts", type=_positive, default=100_000)
    p.add_argument("--days", type=int, default=92)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--distribution", default="skewed")
    p.add_argument("--tx-per-day", type=_positive, default=5)
    p.set_defaults(fn=cmd_backfill)

    p = sub.add_parser("job-diario", help="fecha um dia em DAILY_AGG e remove partições antigas")
    p.add_argument("--day", help="YYYY-MM-DD (default: D-1)")
    p.set_defaults(fn=cmd_job)

    p = sub.add_parser("equivalencia", help="destrutivo: cenário C pela API contra o modelo de referência")
    p.add_argument("--accounts", type=_positive, default=1000)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--now", help="ISO-8601 com fuso (default: agora)")
    p.add_argument("--reset", action="store_true", help="apaga TX e DAILY_AGG antes")
    p.add_argument("--api-url", help="default: http://localhost:8000 (a API do próprio container)")
    p.set_defaults(fn=cmd_equivalencia)

    p = sub.add_parser("gerador", help="gera o dataset NDJSON do ingestor")
    p.add_argument("--accounts", type=_positive, default=100_000)
    p.add_argument("--count", type=_positive, default=200_000)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--distribution", default="skewed")
    p.add_argument("--dataset", default="./data/tx.ndjson")
    p.set_defaults(fn=cmd_gerador)

    p = sub.add_parser("ingestor", help="POST /v1/transacoes à taxa alvo, com ts = agora")
    _load_opts(p)
    p.add_argument("--dataset", default="./data/tx.ndjson")
    p.add_argument("--log", help="default: ./logs/ingestor-<run>.csv")
    p.set_defaults(fn=cmd_ingestor)

    p = sub.add_parser("leitor", help="GET /v1/contas/{id}/features à taxa alvo")
    _load_opts(p)
    _reader_opts(p)
    p.add_argument("--log", help="default: ./logs/leitor-<run>.csv")
    p.set_defaults(fn=cmd_leitor)

    p = sub.add_parser("carga", help="ingestor e leitor ao mesmo tempo, em processos separados")
    _load_opts(p, tps=False)
    p.add_argument("--tps-ingest", type=_positive, default=600)
    p.add_argument("--tps-read", type=_positive, default=600)
    p.add_argument("--dataset", default="./data/tx.ndjson")
    _reader_opts(p)
    p.set_defaults(fn=cmd_carga)

    p = sub.add_parser("relatorio", help="report.md, report.csv e tps-series.csv em <dir>/report")
    p.add_argument("diretorio")
    p.set_defaults(fn=cmd_relatorio)
    return ap


def main(argv: list[str] | None = None) -> int:
    a = parser().parse_args(argv)
    try:
        return a.fn(a)
    except KeyboardInterrupt:
        return errors.UNEXPECTED
    except Exception as e:  # noqa: BLE001 - o código de saída depende do tipo
        code = exit_code_of(e)
        if code == errors.UNEXPECTED:
            traceback.print_exc()
        else:
            print(f"{a.cmd}: {e}", file=sys.stderr)
        return code


if __name__ == "__main__":
    sys.exit(main())
