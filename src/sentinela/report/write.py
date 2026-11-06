"""report.md (uma tabela por execução), report.csv e tps-series.csv."""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

from sentinela.report.build import GroupStats, Latency, SeriesRow


def ms(us: float) -> str:
    return str(Decimal(repr(us)).scaleb(-3).quantize(Decimal("0.1"), ROUND_HALF_UP))


def _pct(v: float) -> str:
    return f"{v * 100:.2f}%"


def _yes(b: bool) -> str:
    return "sim" if b else "NÃO"


def _lat(lat: Latency | None) -> list[str]:
    if lat is None:
        return ["—"] * 4
    return [ms(lat.avg_us), ms(lat.p95_us), ms(lat.p99_us), ms(lat.max_us)]


def markdown(stats: list[GroupStats]) -> str:
    out = ["# Relatório de benchmark\n",
           "Latências em milissegundos, percentis nearest-rank sem interpolação, warmup descartado. "
           "**HTTP** é o tempo visto pelo simulador (rede, FastAPI e banco); **DB** é o tempo da chamada ao "
           "Oracle medido na API (header `Server-Timing`).\n"]
    by_run: dict[str, list[GroupStats]] = {}
    for g in sorted(stats, key=lambda g: (g.start_ms, g.run)):
        by_run.setdefault(g.run, []).append(g)
    for run, groups in by_run.items():
        out.append(f"\n## Execução {run}\n")
        out.append("| Programa | Operação | Ops | Erros | Taxa de erro | Duração (s) | TPS alvo | TPS alcançado | Desvio "
                   "| HTTP avg | HTTP p95 | HTTP p99 | HTTP max | DB avg | DB p95 | DB p99 | DB max "
                   "| TPS ok | Erro ok | Atraso máx (ms) | Fila ok |")
        out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: "
                   "| ---: | ---: | ---: | ---: | --- | --- | ---: | --- |")
        for g in groups:
            cells = [g.program, g.operation, str(g.ops), str(g.errors), _pct(g.error_rate), f"{g.duration_s:.1f}",
                     str(g.target_tps), f"{g.achieved_tps:.1f}", _pct(g.deviation), *_lat(g.http), *_lat(g.db),
                     _yes(g.tps_ok), _yes(g.error_ok), "—" if g.max_start_lag_ms < 0 else str(g.max_start_lag_ms),
                     _yes(g.lag_ok)]
            out.append("| " + " | ".join(cells) + " |")
    return "\n".join(out) + "\n"


def csv(stats: list[GroupStats]) -> str:
    lines = ["run,program,operation,ops,errors,error_rate,duration_s,target_tps,achieved_tps,deviation,"
             "http_min_ms,http_avg_ms,http_p95_ms,http_p99_ms,http_max_ms,db_avg_ms,db_p95_ms,db_p99_ms,db_max_ms,"
             "tps_ok,err_ok,max_start_lag_ms,lag_ok"]
    for g in stats:
        db = ["", "", "", ""] if g.db is None else _lat(g.db)
        lines.append(",".join([g.run, g.program, g.operation, str(g.ops), str(g.errors), f"{g.error_rate:.6f}",
                               f"{g.duration_s:.3f}", str(g.target_tps), f"{g.achieved_tps:.2f}", f"{g.deviation:.6f}",
                               ms(g.http.min_us), *_lat(g.http), *db, str(g.tps_ok).lower(), str(g.error_ok).lower(),
                               str(g.max_start_lag_ms), str(g.lag_ok).lower()]))
    return "\n".join(lines) + "\n"


def series_csv(rows: list[SeriesRow]) -> str:
    lines = ["run,program,operation,second,ops,warmup"]
    lines += [f"{r.run},{r.program},{r.operation},{r.second},{r.ops},{str(r.warmup).lower()}" for r in rows]
    return "\n".join(lines) + "\n"


def write_all(directory: Path, stats: list[GroupStats], series: list[SeriesRow]) -> Path:
    out = directory / "report"
    out.mkdir(parents=True, exist_ok=True)
    (out / "report.md").write_text(markdown(stats), encoding="utf-8")
    (out / "report.csv").write_text(csv(stats), encoding="utf-8")
    (out / "tps-series.csv").write_text(series_csv(series), encoding="utf-8")
    return out
