package sentinela.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

/** Escreve logs sintéticos: 60 leituras a cada 100 ms; latência (i+1)×100 µs; a última com erro. */
final class LogFixtures {
    static final long START = 1_000_000;

    private LogFixtures() {}

    static Path readerLog(Path dir, String mode, String run, long start) throws IOException {
        Path csv = dir.resolve("reader-" + mode + "-" + run + ".csv");
        List<String> lines = new ArrayList<>();
        lines.add(CsvLog.HEADER);
        for (int i = 0; i < 60; i++) {
            String status = i == 59 ? "error" : "ok";
            lines.add((start + i * 100L) + ",reader," + mode + ",read," + (i + 1) + "," + ((i + 1) * 100) + "," + status);
        }
        Files.write(csv, lines);
        RunMeta.write(csv, Map.of("run", run, "start_epoch_ms", Long.toString(start), "warmup", "2", "tps", "10",
                "program", "reader", "mode", mode));
        return csv;
    }
}
