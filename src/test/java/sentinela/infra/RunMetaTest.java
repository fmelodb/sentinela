package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunMetaTest {
    @Test
    void sidecarReplacesCsvExtension() {
        assertEquals(Path.of("logs/reader-oracle+valkey-r1.meta.properties"),
                RunMeta.sidecarOf(Path.of("logs/reader-oracle+valkey-r1.csv")));
    }

    @Test
    void writesReadsAndUpdates(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("ingestor-oracle-r1.csv");
        RunMeta.write(csv, Map.of("tps", "600", "warmup", "30"));
        RunMeta.put(csv, "end_epoch_ms", "123");
        Properties p = RunMeta.read(csv);
        assertEquals("600", p.getProperty("tps"));
        assertEquals("30", p.getProperty("warmup"));
        assertEquals("123", p.getProperty("end_epoch_ms"));
    }
}
