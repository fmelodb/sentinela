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
}
