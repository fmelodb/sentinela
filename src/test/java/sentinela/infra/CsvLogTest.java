package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvLogTest {
    @Test
    void writesHeaderAndOneLinePerRecord(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("sub/reader-oracle-r1.csv");
        try (CsvLog log = new CsvLog(csv, "reader", "oracle")) {
            log.record(1793296800123L, "read", 4711, 842, true);
            log.record(1793296800124L, "read", 12, 1500, false);
        }
        List<String> lines = Files.readAllLines(csv);
        assertEquals(List.of(
                CsvLog.HEADER,
                "1793296800123,reader,oracle,read,4711,842,ok",
                "1793296800124,reader,oracle,read,12,1500,error"), lines);
        assertEquals("ts_epoch_ms,program,mode,operation,account_id,latency_us,status", CsvLog.HEADER);
    }

    @Test
    void keepsAllRecordsUnderVolume(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("ingestor-oracle-r2.csv");
        try (CsvLog log = new CsvLog(csv, "ingestor", "oracle")) {
            for (int i = 0; i < 250_000; i++) {
                log.record(i, "ingest_oracle", i, 1, true);
            }
        }
        assertEquals(250_001, Files.readAllLines(csv).size());
    }
}
