package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.CsvLog;
import sentinela.infra.ErrorReporter;

class LoadRunnerTest {
    private static ErrorReporter quietErrors() {
        return new ErrorReporter(new PrintStream(OutputStream.nullOutputStream()));
    }

    @Test
    void dispatchesAtTargetRateForDuration(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("r.csv");
        LoadRunner.Result result;
        try (CsvLog log = new CsvLog(csv, "reader", "oracle"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.iterate(1, i -> i + 1).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("read", acc -> {}, acc -> acc)),
                    200, Duration.ofSeconds(1), 2, log, errors);
            result = runner.run();
        }
        assertEquals(200, result.dispatched());
        assertFalse(result.sourceExhausted());
        List<String> lines = Files.readAllLines(csv);
        assertEquals(201, lines.size());
        long first = Long.parseLong(lines.get(1).split(",")[0]);
        long last = Long.parseLong(lines.get(200).split(",")[0]);
        assertTrue(last - first >= 900, "operações deveriam se espalhar pelo segundo: " + (last - first) + " ms");
    }

    @Test
    void recordsEachStepAndErrors(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("i.csv");
        try (CsvLog log = new CsvLog(csv, "ingestor", "oracle+valkey"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.rangeClosed(1, 20).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("ingest_oracle", i -> {}, i -> i),
                            new LoadRunner.Step<Long>("ingest_valkey", i -> {
                                if (i % 10 == 0) {
                                    throw new IllegalStateException("valkey fora");
                                }
                            }, i -> i)),
                    1000, Duration.ofSeconds(5), 4, log, errors);
            LoadRunner.Result r = runner.run();
            assertTrue(r.sourceExhausted());
            assertEquals(20, r.dispatched());
            assertEquals(2, errors.total());
        }
        List<String> lines = Files.readAllLines(csv).subList(1, 41);
        assertEquals(20, lines.stream().filter(l -> l.contains(",ingest_oracle,")).count());
        assertEquals(2, lines.stream().filter(l -> l.contains(",ingest_valkey,") && l.endsWith(",error")).count());
    }
}
