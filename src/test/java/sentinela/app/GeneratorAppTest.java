package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.ExitCodes;
import sentinela.workload.Dataset;

class GeneratorAppTest {
    @Test
    void generatesSequentialIdsWithinUniverse(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        int code = GeneratorApp.run(new String[] {"--count", "1000", "--accounts", "50", "--dataset", p.toString()});
        assertEquals(ExitCodes.OK, code);
        List<Dataset.Row> rows = Dataset.readAll(p);
        assertEquals(1000, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).txId());
            assertTrue(rows.get(i).accountId() >= 1 && rows.get(i).accountId() <= 50);
        }
    }

    @Test
    void sameSeedGivesSameFile(@TempDir Path dir) throws Exception {
        Path a = dir.resolve("a.ndjson");
        Path b = dir.resolve("b.ndjson");
        GeneratorApp.run(new String[] {"--count", "500", "--seed", "7", "--dataset", a.toString()});
        GeneratorApp.run(new String[] {"--count", "500", "--seed", "7", "--dataset", b.toString()});
        assertEquals(Files.readString(a), Files.readString(b));
    }
}
