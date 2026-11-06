from pathlib import Path

from sentinela.report import build, write
from sentinela.sim.csvlog import CsvLog, write_meta


def _log(tmp: Path, name: str, start: int, rows, **meta) -> None:
    p = tmp / f"{name}.csv"
    with CsvLog(p, name.split("-")[0]) as log:
        for r in rows:
            log.record(*r)
    write_meta(p, {"run": "r1", "tps": 10, "warmup": 1, "start_epoch_ms": start, **meta})


def test_nearest_rank():
    v = list(range(1, 101))
    assert build.nearest_rank(v, 950) == 95
    assert build.nearest_rank(v, 990) == 99
    assert build.nearest_rank([7], 990) == 7


def test_relatorio_descarta_warmup_e_separa_http_e_db(tmp_path: Path):
    start = 1_000_000
    rows = [(start + 500, "read", 1, 99_000, 50_000, True)]  # warmup
    rows += [(start + 1000 + i * 100, "read", 1, 1000 * (i + 1), 500, True) for i in range(10)]
    rows += [(start + 2100, "read", 2, 5, None, False)]
    _log(tmp_path, "leitor-r1", start, rows, measured_starts=11, late_starts=0, max_start_lag_ms=3)
    files = build.load(tmp_path)
    [g] = build.build(files)
    assert g.ops == 10 and g.errors == 1
    assert g.http.max_us == 10_000 and g.http.p95_us == 10_000 and g.http.min_us == 1000
    assert g.db is not None and g.db.avg_us == 500
    assert g.lag_ok and g.max_start_lag_ms == 3 and not g.error_ok
    md = write.markdown([g])
    assert "## Execução r1" in md and "| leitor | read | 10 | 1 |" in md
    assert write.csv([g]).count("\n") == 2
    s = build.series(files)
    assert s[0].warmup and not s[-1].warmup


def test_fila_ruim(tmp_path: Path):
    _log(tmp_path, "ingestor-r1", 0, [(1000, "ingest", 1, 10, 5, True)], measured_starts=100, late_starts=2)
    [g] = build.build(build.load(tmp_path))
    assert not g.lag_ok


def test_csv_sem_meta_e_ignorado(tmp_path: Path, capsys):
    (tmp_path / "solto.csv").write_text("x\n")
    import io

    warn = io.StringIO()
    assert build.load(tmp_path, warn) == []
    assert "sem .meta.json" in warn.getvalue()


def test_ms():
    assert write.ms(1250) == "1.3"
    assert write.ms(1249.9) == "1.2"
