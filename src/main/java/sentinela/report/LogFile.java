package sentinela.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public record LogFile(Path csv, Properties meta, List<LogRow> rows) {
    public String run() {
        return meta.getProperty("run", csv.getFileName().toString());
    }

    public long startMs() {
        return Long.parseLong(meta.getProperty("start_epoch_ms"));
    }

    public int warmupS() {
        return Integer.parseInt(meta.getProperty("warmup", "0"));
    }

    public int targetTps() {
        return Integer.parseInt(meta.getProperty("tps"));
    }

    /** Itens que começaram depois do warmup; -1 se o log não traz a medição de fila. */
    public long measuredStarts() {
        return Long.parseLong(meta.getProperty("measured_starts", "-1"));
    }

    /** Itens que começaram mais de LoadRunner.LATE_THRESHOLD_MS depois do agendado; -1 se ausente. */
    public long lateStarts() {
        return Long.parseLong(meta.getProperty("late_starts", "-1"));
    }

    public long maxStartLagMs() {
        return Long.parseLong(meta.getProperty("max_start_lag_ms", "-1"));
    }
}
