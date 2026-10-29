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
                    200, Duration.ofSeconds(1), Duration.ZERO, 2, log, errors);
            result = runner.run();
        }
        assertEquals(200, result.dispatched());
        assertFalse(result.sourceExhausted());
        assertEquals(0, result.lateStarts(), "workers folgados não atrasam: " + result.maxStartLagMs() + " ms");
        assertEquals(200, result.measuredStarts());
        List<String> lines = Files.readAllLines(csv);
        assertEquals(201, lines.size());
        long first = Long.parseLong(lines.get(1).split(",")[0]);
        long last = Long.parseLong(lines.get(200).split(",")[0]);
        assertTrue(last - first >= 900, "operações deveriam se espalhar pelo segundo: " + (last - first) + " ms");
    }

    /** Fila escondida: 1 worker a 30 ms por item não acompanha 100/s; o atraso de início precisa aparecer. */
    @Test
    void reportsStartLagWhenWorkersSaturate(@TempDir Path dir) throws Exception {
        try (CsvLog log = new CsvLog(dir.resolve("s.csv"), "reader", "oracle"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.iterate(1, i -> i + 1).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("read", acc -> {
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }, acc -> acc)),
                    100, Duration.ofSeconds(1), Duration.ZERO, 1, log, errors);
            LoadRunner.Result r = runner.run();
            assertEquals(100, r.measuredStarts());
            assertTrue(r.lateStarts() > 50, "itens atrasados: " + r.lateStarts());
            assertTrue(r.maxStartLagMs() >= 1000, "atraso máximo: " + r.maxStartLagMs() + " ms");
        }
    }

    @Test
    void warmupStartsAreNotMeasured(@TempDir Path dir) throws Exception {
        try (CsvLog log = new CsvLog(dir.resolve("w.csv"), "reader", "oracle"); ErrorReporter errors = quietErrors()) {
            LoadRunner<Long> runner = new LoadRunner<>(LongStream.iterate(1, i -> i + 1).boxed().iterator(),
                    List.of(new LoadRunner.Step<Long>("read", acc -> {}, acc -> acc)),
                    100, Duration.ofSeconds(1), Duration.ofMillis(500), 2, log, errors);
            LoadRunner.Result r = runner.run();
            assertTrue(r.measuredStarts() >= 45 && r.measuredStarts() <= 55, "medidos: " + r.measuredStarts());
        }
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
                    1000, Duration.ofSeconds(5), Duration.ZERO, 4, log, errors);
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
