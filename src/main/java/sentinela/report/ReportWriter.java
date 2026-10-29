package sentinela.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Markdown com uma tabela por execução e a comparação dos modos; CSVs para planilha. */
public final class ReportWriter {
    private static final List<String> MODES = List.of("oracle", "oracle+valkey");
    private static final List<String> OPERATIONS = List.of("read", "ingest_oracle", "ingest_valkey");

    private ReportWriter() {}

    public static String ms(double us) {
        return BigDecimal.valueOf(us).movePointLeft(3).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    public static String markdown(List<GroupStats> stats) {
        StringBuilder sb = new StringBuilder("# Relatório de benchmark\n\nLatências em milissegundos. "
                + "Percentis nearest-rank, sem interpolação. Warmup descartado.\n");
        Map<String, List<GroupStats>> byRun = new LinkedHashMap<>();
        stats.stream().sorted(Comparator.comparingLong(GroupStats::startMs).thenComparing(GroupStats::run))
                .forEach(g -> byRun.computeIfAbsent(g.run(), k -> new ArrayList<>()).add(g));
        byRun.forEach((run, groups) -> {
            sb.append("\n## Execução ").append(run).append("\n\n");
            sb.append("| Programa | Modo | Operação | Ops | Erros | Taxa de erro | Duração (s) | TPS alvo | TPS alcançado"
                    + " | Desvio | min | avg | p95 | p99 | max | TPS ok | Erro ok | Atraso máx (ms) | Fila ok |\n");
            sb.append("| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: | --- |\n");
            for (GroupStats g : groups) {
                sb.append("| ").append(g.program()).append(" | ").append(g.mode()).append(" | ").append(g.operation())
                        .append(" | ").append(g.ops()).append(" | ").append(g.errors())
                        .append(" | ").append(pct(g.errorRate())).append(" | ").append(fmt1(g.durationS()))
                        .append(" | ").append(g.targetTps()).append(" | ").append(fmt1(g.achievedTps()))
                        .append(" | ").append(pct(g.deviation()))
                        .append(" | ").append(ms(g.minUs())).append(" | ").append(ms(g.avgUs()))
                        .append(" | ").append(ms(g.p95Us())).append(" | ").append(ms(g.p99Us()))
                        .append(" | ").append(ms(g.maxUs()))
                        .append(" | ").append(g.tpsOk() ? "sim" : "NÃO").append(" | ").append(g.errorOk() ? "sim" : "NÃO")
                        .append(" | ").append(g.maxStartLagMs() < 0 ? "—" : Long.toString(g.maxStartLagMs()))
                        .append(" | ").append(g.lagOk() ? "sim" : "NÃO")
                        .append(" |\n");
            }
        });
        appendComparison(sb, stats);
        return sb.toString();
    }

    public static String csv(List<GroupStats> stats) {
        StringBuilder sb = new StringBuilder("run,program,mode,operation,ops,errors,error_rate,duration_s,target_tps,"
                + "achieved_tps,deviation,min_ms,avg_ms,p95_ms,p99_ms,max_ms,tps_ok,err_ok,max_start_lag_ms,lag_ok\n");
        for (GroupStats g : stats) {
            sb.append(String.join(",", g.run(), g.program(), g.mode(), g.operation(),
                    Long.toString(g.ops()), Long.toString(g.errors()), fmt(g.errorRate(), 6), fmt(g.durationS(), 3),
                    Integer.toString(g.targetTps()), fmt(g.achievedTps(), 2), fmt(g.deviation(), 6),
                    ms(g.minUs()), ms(g.avgUs()), ms(g.p95Us()), ms(g.p99Us()), ms(g.maxUs()),
                    Boolean.toString(g.tpsOk()), Boolean.toString(g.errorOk()),
                    Long.toString(g.maxStartLagMs()), Boolean.toString(g.lagOk()))).append('\n');
        }
        return sb.toString();
    }

    public static String seriesCsv(List<SeriesRow> rows) {
        StringBuilder sb = new StringBuilder("run,program,mode,operation,second,ops,warmup\n");
        for (SeriesRow r : rows) {
            sb.append(String.join(",", r.run(), r.program(), r.mode(), r.operation(), Long.toString(r.second()),
                    Long.toString(r.ops()), Boolean.toString(r.warmup()))).append('\n');
        }
        return sb.toString();
    }

    private static void appendComparison(StringBuilder sb, List<GroupStats> stats) {
        Map<String, String> latestRun = new LinkedHashMap<>();
        for (String mode : MODES) {
            stats.stream().filter(g -> g.mode().equals(mode)).max(Comparator.comparingLong(GroupStats::startMs))
                    .ifPresent(g -> latestRun.put(mode, g.run()));
        }
        if (latestRun.size() < MODES.size()) {
            return;
        }
        sb.append("\n## Comparação dos modos\n\n");
        sb.append("Execuções: oracle = ").append(latestRun.get("oracle"))
                .append("; oracle+valkey = ").append(latestRun.get("oracle+valkey")).append(".\n\n");
        sb.append("| Operação | Medida | oracle | oracle+valkey |\n| --- | --- | ---: | ---: |\n");
        Map<String, Function<GroupStats, String>> measures = new LinkedHashMap<>();
        measures.put("TPS alcançado", g -> fmt1(g.achievedTps()));
        measures.put("avg (ms)", g -> ms(g.avgUs()));
        measures.put("p95 (ms)", g -> ms(g.p95Us()));
        measures.put("p99 (ms)", g -> ms(g.p99Us()));
        measures.put("max (ms)", g -> ms(g.maxUs()));
        for (String op : OPERATIONS) {
            Optional<GroupStats> o = find(stats, latestRun.get("oracle"), "oracle", op);
            Optional<GroupStats> v = find(stats, latestRun.get("oracle+valkey"), "oracle+valkey", op);
            if (o.isEmpty() && v.isEmpty()) {
                continue;
            }
            measures.forEach((name, f) -> sb.append("| ").append(op).append(" | ").append(name).append(" | ")
                    .append(o.map(f).orElse("—")).append(" | ").append(v.map(f).orElse("—")).append(" |\n"));
        }
    }

    private static Optional<GroupStats> find(List<GroupStats> stats, String run, String mode, String op) {
        return stats.stream().filter(g -> g.run().equals(run) && g.mode().equals(mode) && g.operation().equals(op))
                .findFirst();
    }

    private static String fmt1(double v) {
        return fmt(v, 1);
    }

    private static String pct(double v) {
        return fmt(v * 100, 2) + "%";
    }

    private static String fmt(double v, int scale) {
        return String.format(Locale.ROOT, "%." + scale + "f", v);
    }
}
