package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadPathsTest {
    @Test
    void defaultRunIsUtcTimestamp() {
        assertEquals("20261029-150730", LoadPaths.defaultRun(Instant.parse("2026-10-29T15:07:30.999Z")));
    }

    @Test
    void defaultLogFollowsSpecPattern() {
        assertEquals(Path.of("./logs/reader-oracle+valkey-r1.csv"), LoadPaths.defaultLog("reader", Mode.ORACLE_VALKEY, "r1"));
    }

    @Test
    void metaHasFieldsTheReportNeeds() {
        Map<String, String> m = LoadPaths.meta("ingestor", Mode.ORACLE, "r1", 600, 300, 30, 8, 123L);
        assertEquals("ingestor", m.get("program"));
        assertEquals("oracle", m.get("mode"));
        assertEquals("r1", m.get("run"));
        assertEquals("600", m.get("tps"));
        assertEquals("300", m.get("duration"));
        assertEquals("30", m.get("warmup"));
        assertEquals("8", m.get("threads"));
        assertEquals("123", m.get("start_epoch_ms"));
        assertNotNull(m.get("java.version"));
        assertNotNull(m.get("host"));
    }
}
