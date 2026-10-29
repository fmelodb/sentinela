package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportWriterTest {
    private static GroupStats g(String run, long start, String program, String mode, String op, double avgUs) {
        return new GroupStats(run, start, program, mode, op, 1000, 0, 0.0, 100.0, 10, 10.0, 0.0,
                500, avgUs, 1500, 2500, 9000, true, true, 250, true);
    }

    @Test
    void formatsMillisecondsWithOneDecimalHalfUp() {
        assertEquals("0.8", ReportWriter.ms(842));
        assertEquals("1.1", ReportWriter.ms(1050));
        assertEquals("4.0", ReportWriter.ms(4000));
    }

    @Test
    void markdownHasOneTablePerRunAndComparisonWhenBothModes() {
        String md = ReportWriter.markdown(List.of(
                g("r1", 1, "reader", "oracle", "read", 4000),
                g("r2", 2, "reader", "oracle+valkey", "read", 800),
                g("r2", 2, "ingestor", "oracle+valkey", "ingest_valkey", 300)));
        assertTrue(md.contains("## Execução r1"));
        assertTrue(md.contains("## Execução r2"));
        assertTrue(md.contains("## Comparação dos modos"));
        assertTrue(md.contains("| Atraso máx (ms) | Fila ok |"), md);
        assertTrue(md.contains("| read | avg (ms) | 4.0 | 0.8 |"), md);
        assertTrue(md.contains("| ingest_valkey | avg (ms) | — | 0.3 |"), md);
    }

    @Test
    void markdownWithoutComparisonWhenSingleMode() {
        String md = ReportWriter.markdown(List.of(g("r1", 1, "reader", "oracle", "read", 4000)));
        assertFalse(md.contains("Comparação dos modos"));
    }

    @Test
    void comparisonUsesLatestRunOfEachMode() {
        String md = ReportWriter.markdown(List.of(
                g("velho", 1, "reader", "oracle", "read", 9000),
                g("novo", 5, "reader", "oracle", "read", 4000),
                g("vk", 6, "reader", "oracle+valkey", "read", 800)));
        assertTrue(md.contains("| read | avg (ms) | 4.0 | 0.8 |"), md);
    }

    @Test
    void csvHasHeaderAndOneLinePerGroup() {
        String csv = ReportWriter.csv(List.of(g("r1", 1, "reader", "oracle", "read", 4000)));
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("run,program,mode,operation,ops,errors"));
        assertTrue(lines[1].startsWith("r1,reader,oracle,read,1000,0,"));
        assertTrue(lines[0].endsWith(",max_start_lag_ms,lag_ok"), lines[0]);
        assertTrue(lines[1].endsWith(",250,true"), lines[1]);
    }

    @Test
    void seriesCsvListsSeconds() {
        String csv = ReportWriter.seriesCsv(List.of(new SeriesRow("r1", "reader", "oracle", "read", 0, 10, true)));
        assertEquals("run,program,mode,operation,second,ops,warmup\nr1,reader,oracle,read,0,10,true\n", csv);
    }
}
