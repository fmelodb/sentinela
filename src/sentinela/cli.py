"""sentinela <programa> [argumentos]. Códigos de saída: 0 ok, 1 verificação falhou, 2 uso, 3 store indisponível,
4 rodada inválida, 5 erro inesperado.

No container da API (falam com o Oracle):  api, schema, job-diario, backfill, equivalencia, importar --modo direto
No container do simulador (só HTTP):        gerador, ingestor, leitor, carga, importar --modo api, relatorio
"""

from __future__ import annotations

import argparse
import sys
import traceback
from datetime import date, datetime, timedelta
from pathlib import Path

from sentinela import errors
from sentinela.config import Env
from sentinela.errors import UsageError

FONTES = ("trx", "abr", "vu")


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
    try:
        v = int(s)
    except ValueError:
        raise argparse.ArgumentTypeError(f"precisa ser inteiro: {s}") from None
    if v < 0:
        raise argparse.ArgumentTypeError(f"não pode ser negativo: {s}")
    return v


def _rate(s: str) -> float:
    try:
        v = float(s)
    except ValueError:
        raise argparse.ArgumentTypeError(f"precisa ser número: {s}") from None
    if v < 0:
        raise argparse.ArgumentTypeError(f"não pode ser negativo: {s}")
    return v


def _fraction(s: str) -> float:
    v = _rate(s)
    if v > 1:
        raise argparse.ArgumentTypeError(f"precisa estar entre 0 e 1: {s}")
    return v


def _local_now(env: Env) -> datetime:
    return datetime.now(env.zone).replace(tzinfo=None, microsecond=0)


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
    from sentinela.db.maintenance import RETENTION_DAYS, Maintenance
    from sentinela.db.pool import connect

    env = Env.from_environ()
    today = datetime.now(env.zone).date()
    if a.day is not None:
        today = parse_day(a.day, today) + timedelta(days=1)
    with connect(env) as conn:
        dropped = Maintenance(conn).drop_old_partitions(today)
    print(f"job-diario: retenção de {RETENTION_DAYS} dias antes de {today}; partições removidas: "
          f"{', '.join(dropped) or 'nenhuma'}", file=sys.stderr)
    return errors.OK


def cmd_backfill(a: argparse.Namespace) -> int:
    from sentinela import backfill
    from sentinela.db.pool import connect
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    if a.dias > backfill.MAX_DAYS:
        raise UsageError(f"--dias no máximo {backfill.MAX_DAYS}")
    env = Env.from_environ()
    now = _local_now(env)
    with connect(env) as conn:
        total = backfill.run(conn, now, a.clientes, a.dias, a.seed, a.distribution,
                             {"trx": a.trx_dia, "abr": a.abr_dia, "vu": a.vu_dia})
    print(f"backfill: {total['trx']} TRX, {total['abr']} ABR, {total['vu']} VU; {a.clientes} clientes "
          f"({a.distribution}), {a.dias} dias até {now} ({env.zone})", file=sys.stderr)
    return errors.OK


def cmd_equivalencia(a: argparse.Namespace) -> int:
    import asyncio

    import aiohttp

    from sentinela.db.maintenance import Maintenance
    from sentinela.db.pool import connect
    from sentinela.verification import equivalence, scenarios

    if not a.reset:
        raise UsageError("a equivalência esvazia TRX, ABR e VU antes de ingerir os cenários; confirme com --reset")
    env = Env.from_environ()
    with connect(env) as conn:
        Maintenance(conn).reset()
    api_url = (a.api_url or env.api_url).rstrip("/")

    async def go() -> equivalence.Report:
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60)) as session:
            client = equivalence.AiohttpClient(session, api_url)
            return await equivalence.run(client, scenarios.all_scenarios(a.seed, a.clientes),
                                         lambda m: print(f"equivalencia: {m}", file=sys.stderr))

    rep = asyncio.run(go())
    for line in rep.lines:
        print(line)
    print(f"equivalencia: {rep.passed} PASS, {rep.failed} FAIL ({api_url})", file=sys.stderr)
    return errors.OK if rep.failed == 0 else errors.VERIFICATION_FAILED


def cmd_importar(a: argparse.Namespace) -> int:
    import asyncio

    from sentinela import importar

    path = Path(a.arquivo)
    env = Env.from_environ()
    if a.modo == "direto":
        from sentinela.db.pool import connect

        with connect(env) as conn:
            s = importar.direto(conn, env.zone, a.fonte, path, a.sep)
    else:
        s = asyncio.run(importar.api((a.api_url or env.api_url).rstrip("/"), a.fonte, path, a.sep, a.lote))
    importar.print_summary(a.fonte, a.modo, path, s)
    return errors.OK if s.invalidas == 0 else errors.VERIFICATION_FAILED


# ---------------------------------------------------------------- simulador

def cmd_gerador(a: argparse.Namespace) -> int:
    from sentinela.sim import synth
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    out = synth.generate(Path(a.dir), a.clientes, a.distribution, a.seed, {"trx": a.trx, "abr": a.abr, "vu": a.vu},
                         _local_now(Env.from_environ()), a.dias)
    print(f"gerador: {', '.join(f'{n} {f.upper()}' for f, n in out.items())}; {a.clientes} clientes "
          f"({a.distribution}), seed {a.seed} -> {a.dir}", file=sys.stderr)
    return errors.OK


def _load_args(a: argparse.Namespace, run: str, log: str | None, tps: int | None = None) -> object:
    from sentinela.sim.load import LoadArgs

    return LoadArgs(api_url=(a.api_url or Env.from_environ().api_url).rstrip("/"), tps=tps or a.tps,
                    duration=a.duration, warmup=a.warmup, concurrency=a.concurrency, run=run,
                    log=Path(log) if log else None)


def cmd_ingestor(a: argparse.Namespace) -> int:
    from sentinela.sim import load
    from sentinela.sim.synth import dataset_path

    path = Path(a.dataset) if a.dataset else dataset_path(Path(a.dir), a.fonte)
    return load.run_ingestor(_load_args(a, a.run or load.default_run(), a.log), a.fonte, path, a.replay)


def cmd_leitor(a: argparse.Namespace) -> int:
    from sentinela.sim import load
    from sentinela.sim.samplers import check_distribution
    from sentinela.sim.synth import dataset_path

    check_distribution(a.distribution)
    return load.run_leitor(_load_args(a, a.run or load.default_run(), a.log), a.clientes, a.seed, a.distribution,
                           a.fracao_get, dataset_path(Path(a.dir), "trx"), a.tps_trx)


def cmd_carga(a: argparse.Namespace) -> int:
    from sentinela.sim import load
    from sentinela.sim.samplers import check_distribution

    check_distribution(a.distribution)
    run = a.run or load.default_run()
    ingest = {f: _load_args(a, run, None, tps) for f, tps in (("trx", a.tps_trx), ("abr", a.tps_abr),
                                                              ("vu", a.tps_vu)) if tps > 0}
    read = _load_args(a, run, None, a.tps_read) if a.tps_read > 0 else None
    return load.carga(ingest, read, Path(a.dir), a.replay, a.clientes, a.seed, a.distribution, a.fracao_get)


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


def _client_opts(p: argparse.ArgumentParser) -> None:
    p.add_argument("--clientes", type=_positive, default=100_000)
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

    p = sub.add_parser("schema", help="cria TRX, ABR e VU (idempotente)")
    p.add_argument("--arquivo", help="default: $SENTINELA_SCHEMA ou db/01_schema.sql")
    p.set_defaults(fn=cmd_schema)

    p = sub.add_parser("job-diario", help="remove partições de TRX, ABR e VU fora da retenção (93 dias)")
    p.add_argument("--day", help="último dia fechado, YYYY-MM-DD (default: D-1)")
    p.set_defaults(fn=cmd_job)

    p = sub.add_parser("backfill", help="história sintética de TRX, ABR e VU direto no banco (destrutivo)")
    _client_opts(p)
    p.add_argument("--dias", type=_positive, default=92)
    p.add_argument("--trx-dia", type=_rate, default=1.0, help="TRX por cliente por dia (default 1)")
    p.add_argument("--abr-dia", type=_rate, default=0.5, help="ABR por cliente por dia (default 0,5)")
    p.add_argument("--vu-dia", type=_rate, default=0.3, help="VU por cliente por dia (default 0,3)")
    p.set_defaults(fn=cmd_backfill)

    p = sub.add_parser("equivalencia", help="cenários do Excel e da spec pela API × referência (destrutivo)")
    p.add_argument("--reset", action="store_true", help="confirma que TRX, ABR e VU serão esvaziadas")
    p.add_argument("--clientes", type=_positive, default=25, help="clientes do cenário aleatório (default 25)")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--api-url", help="default: $API_URL")
    p.set_defaults(fn=cmd_equivalencia)

    p = sub.add_parser("importar", help="carrega um arquivo de uma fonte (NDJSON, JSON ou CSV, nomes originais)")
    p.add_argument("--fonte", required=True, choices=FONTES)
    p.add_argument("--arquivo", required=True)
    p.add_argument("--modo", choices=("api", "direto"), default="api",
                   help="api: endpoints de lote (default); direto: carga em massa no Oracle")
    p.add_argument("--sep", default=",", help="separador do CSV (default ,)")
    p.add_argument("--lote", type=_positive, default=1000, help="linhas por chamada no modo api (default 1000)")
    p.add_argument("--api-url", help="default: $API_URL")
    p.set_defaults(fn=cmd_importar)

    p = sub.add_parser("gerador", help="gera <dir>/{trx,abr,vu}.ndjson para os ingestores")
    _client_opts(p)
    p.add_argument("--trx", type=_non_negative, default=200_000)
    p.add_argument("--abr", type=_non_negative, default=60_000)
    p.add_argument("--vu", type=_non_negative, default=30_000)
    p.add_argument("--dias", type=_positive, default=1, help="eventos espalhados nos N dias até agora (default 1)")
    p.add_argument("--dir", default="./data")
    p.set_defaults(fn=cmd_gerador)

    p = sub.add_parser("ingestor", help="POST /v1/<fonte> à taxa alvo, com o instante = agora")
    _load_opts(p)
    p.add_argument("--fonte", choices=FONTES, default="trx")
    p.add_argument("--dir", default="./data")
    p.add_argument("--dataset", help="default: <dir>/<fonte>.ndjson")
    p.add_argument("--replay", action="store_true", help="usa o instante do arquivo em vez de agora")
    p.add_argument("--log", help="default: ./logs/ingestor_<fonte>-<run>.csv")
    p.set_defaults(fn=cmd_ingestor)

    p = sub.add_parser("leitor", help="POST /v1/features (compra sintética) e GET /v1/features/{ID_TRX}")
    _load_opts(p)
    _client_opts(p)
    p.add_argument("--fracao-get", type=_fraction, default=0.0,
                   help="fração de GET por ID_TRX recém-ingerido (precisa do ingestor de TRX rodando; default 0)")
    p.add_argument("--tps-trx", type=_non_negative, default=0, help="TPS do ingestor de TRX, para achar os IDs")
    p.add_argument("--dir", default="./data")
    p.add_argument("--log", help="default: ./logs/leitor-<run>.csv")
    p.set_defaults(fn=cmd_leitor)

    p = sub.add_parser("carga", help="ingestores de TRX, ABR e VU e o leitor ao mesmo tempo")
    _load_opts(p, tps=False)
    _client_opts(p)
    p.add_argument("--tps-trx", type=_non_negative, default=600)
    p.add_argument("--tps-abr", type=_non_negative, default=200)
    p.add_argument("--tps-vu", type=_non_negative, default=100)
    p.add_argument("--tps-read", type=_non_negative, default=600)
    p.add_argument("--fracao-get", type=_fraction, default=0.5, help="fração das leituras por GET (default 0,5)")
    p.add_argument("--dir", default="./data")
    p.add_argument("--replay", action="store_true", help="usa o instante dos arquivos em vez de agora")
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
