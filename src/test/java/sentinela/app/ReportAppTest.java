package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;

class ReportAppTest {
    @Test
    void writesThreeOutputs(@TempDir Path dir) throws Exception {
        sentinela.report.LogFixturesAccess.readerLog(dir, "oracle", "r1");
        assertEquals(ExitCodes.OK, ReportApp.run(new String[] {dir.toString()}));
        assertTrue(Files.readString(dir.resolve("report/report.md")).contains("## Execução r1"));
        assertTrue(Files.exists(dir.resolve("report/report.csv")));
        assertTrue(Files.exists(dir.resolve("report/tps-series.csv")));
    }

    @Test
    void requiresExactlyOneDirectory() {
        assertThrows(UsageException.class, () -> ReportApp.run(new String[0]));
        assertThrows(UsageException.class, () -> ReportApp.run(new String[] {"a", "b"}));
    }
}
