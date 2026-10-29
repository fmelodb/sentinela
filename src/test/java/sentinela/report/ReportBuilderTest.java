package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

class ReportBuilderTest {
    private final ByteArrayOutputStream warnings = new ByteArrayOutputStream();
    private final PrintStream warn = new PrintStream(warnings, true, StandardCharsets.UTF_8);

    @Test
    void computesStatsAfterWarmup(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        List<GroupStats> stats = ReportBuilder.build(LogLoader.load(dir, warn), warn);
        assertEquals(1, stats.size());
        GroupStats g = stats.get(0);
        assertEquals("r1", g.run());
        assertEquals("reader", g.program());
        assertEquals("oracle", g.mode());
        assertEquals("read", g.operation());
        assertEquals(39, g.ops(), "linhas 20..58 ok depois de 2 s de warmup");
        assertEquals(1, g.errors());
        assertEquals(0.025, g.errorRate(), 1e-9);
        assertEquals(3.9, g.durationS(), 1e-9);
        assertEquals(10.0, g.achievedTps(), 1e-9);
        assertEquals(0.0, g.deviation(), 1e-9);
        assertEquals(2100, g.minUs());
        assertEquals(5900, g.maxUs());
        assertEquals(4000.0, g.avgUs(), 1e-9);
        assertEquals(5800, g.p95Us(), "38º de 39 valores");
        assertEquals(5900, g.p99Us());
        assertTrue(g.tpsOk());
        assertFalse(g.errorOk());
    }

    @Test
    void skipsCsvWithoutSidecarWithWarning(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        Files.writeString(dir.resolve("orfao.csv"), CsvLog.HEADER + "\n");
        List<LogFile> files = LogLoader.load(dir, warn);
        assertEquals(1, files.size());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("orfao.csv"));
    }

    @Test
    void skipsGroupEmptyAfterWarmupWithWarning(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("reader-oracle-r9.csv");
        Files.writeString(csv, CsvLog.HEADER + "\n" + LogFixtures.START + ",reader,oracle,read,1,100,ok\n");
        RunMeta.write(csv, Map.of("run", "r9", "start_epoch_ms", Long.toString(LogFixtures.START), "warmup", "30",
                "tps", "10"));
        List<GroupStats> stats = ReportBuilder.build(LogLoader.load(dir, warn), warn);
        assertTrue(stats.isEmpty());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("r9"));
    }

    @Test
    void skipsMalformedLinesWithWarning(@TempDir Path dir) throws Exception {
        Path csv = LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        Files.writeString(csv, "lixo,sem,colunas\n", java.nio.file.StandardOpenOption.APPEND);
        List<LogFile> files = LogLoader.load(dir, warn);
        assertEquals(60, files.get(0).rows().size());
        assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("malformada"));
    }

    @Test
    void seriesCountsOkOperationsPerSecondFillingGaps(@TempDir Path dir) throws Exception {
        LogFixtures.readerLog(dir, "oracle", "r1", LogFixtures.START);
        List<SeriesRow> series = ReportBuilder.series(LogLoader.load(dir, warn));
        assertEquals(6, series.size(), "segundos 0..5");
        assertEquals(10, series.get(0).ops());
        assertTrue(series.get(0).warmup());
        assertTrue(series.get(1).warmup());
        assertFalse(series.get(2).warmup());
        assertEquals(9, series.get(5).ops(), "a última linha é erro");
    }
}
