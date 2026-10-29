package sentinela.report;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Agrupa por run, program, mode e operation; descarta o warmup; calcula TPS e latências. */
public final class ReportBuilder {
    static final double MAX_TPS_DEVIATION = 0.05;
    static final double MAX_ERROR_RATE = 0.001;
    /** Fração máxima de inícios atrasados (fila) após o warmup antes de a rodada ser marcada. */
    static final double MAX_LATE_START_RATE = 0.01;

    private ReportBuilder() {}

    public static List<GroupStats> build(List<LogFile> files, PrintStream warn) {
        List<GroupStats> out = new ArrayList<>();
        for (LogFile f : files) {
            long cutoff = f.startMs() + f.warmupS() * 1000L;
            Map<String, List<LogRow>> byOp = f.rows().stream()
                    .filter(r -> r.tsMs() >= cutoff)
                    .collect(Collectors.groupingBy(LogRow::operation, TreeMap::new, Collectors.toList()));
            if (byOp.isEmpty()) {
                warn.println("aviso: run " + f.run() + " (" + f.csv().getFileName() + ") sem operações após o warmup");
                continue;
            }
            byOp.forEach((op, rows) -> {
                GroupStats g = stats(f, op, rows);
                if (g == null) {
                    warn.println("aviso: run " + f.run() + " operação " + op + " sem operações ok após o warmup");
                } else {
                    out.add(g);
                }
            });
        }
        out.sort(Comparator.comparing(GroupStats::run).thenComparing(GroupStats::program)
                .thenComparing(GroupStats::mode).thenComparing(GroupStats::operation));
        return out;
    }

    public static List<SeriesRow> series(List<LogFile> files) {
        List<SeriesRow> out = new ArrayList<>();
        for (LogFile f : files) {
            Map<String, List<LogRow>> byOp = f.rows().stream()
                    .collect(Collectors.groupingBy(LogRow::operation, TreeMap::new, Collectors.toList()));
            byOp.forEach((op, rows) -> {
                long maxSecond = rows.stream().mapToLong(r -> Math.floorDiv(r.tsMs() - f.startMs(), 1000)).max().orElse(-1);
                long[] counts = new long[(int) (maxSecond + 1)];
                for (LogRow r : rows) {
                    long s = Math.floorDiv(r.tsMs() - f.startMs(), 1000);
                    if (r.ok() && s >= 0) {
                        counts[(int) s]++;
                    }
                }
                LogRow sample = rows.get(0);
                for (int s = 0; s < counts.length; s++) {
                    out.add(new SeriesRow(f.run(), sample.program(), sample.mode(), op, s, counts[s], s < f.warmupS()));
                }
            });
        }
        return out;
    }

    private static GroupStats stats(LogFile f, String op, List<LogRow> rows) {
        long[] lat = rows.stream().filter(LogRow::ok).mapToLong(LogRow::latencyUs).sorted().toArray();
        if (lat.length == 0) {
            return null;
        }
        long errors = rows.size() - lat.length;
        long first = rows.stream().mapToLong(LogRow::tsMs).min().orElseThrow();
        long last = rows.stream().mapToLong(LogRow::tsMs).max().orElseThrow();
        double durationS = Math.max(1, last - first) / 1000.0;
        double achieved = lat.length / durationS;
        int target = f.targetTps();
        double deviation = Math.abs(achieved - target) / target;
        double errorRate = errors / (double) rows.size();
        double avg = 0;
        for (long v : lat) {
            avg += v;
        }
        avg /= lat.length;
        LogRow sample = rows.get(0);
        return new GroupStats(f.run(), f.startMs(), sample.program(), sample.mode(), op,
                lat.length, errors, errorRate, durationS, target, achieved, deviation,
                lat[0], avg, Stats.nearestRank(lat, 950), Stats.nearestRank(lat, 990), lat[lat.length - 1],
                deviation < MAX_TPS_DEVIATION, errorRate < MAX_ERROR_RATE,
                f.maxStartLagMs(), lagOk(f));
    }

    private static boolean lagOk(LogFile f) {
        long measured = f.measuredStarts();
        long late = f.lateStarts();
        if (measured < 0 || late < 0) {
            return true; // log sem medição de fila
        }
        return late <= MAX_LATE_START_RATE * measured;
    }
}
