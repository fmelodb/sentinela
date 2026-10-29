package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sentinela.infra.UsageException;

class DatasetTest {
    @Test
    void roundTripsRows(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("a/tx.ndjson");
        List<Dataset.Row> rows = List.of(new Dataset.Row(1, 4711, 12345), new Dataset.Row(2, 7, 100));
        Dataset.write(p, rows.iterator());
        assertEquals("{\"tx_id\":1,\"account_id\":4711,\"amount\":123.45}", Files.readAllLines(p).get(0));
        assertEquals(rows, Dataset.readAll(p));
    }

    @Test
    void ignoresBlankLinesAndFieldOrder(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "\n{\"amount\":1.5,\"account_id\":3,\"tx_id\":9}\n   \n");
        assertEquals(List.of(new Dataset.Row(9, 3, 150)), Dataset.readAll(p));
    }

    @Test
    void reportsLineNumberOfMalformedLine(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "{\"tx_id\":1,\"account_id\":1,\"amount\":1.00}\n{\"tx_id\":2,\"account_id\":\n");
        UsageException e = assertThrows(UsageException.class, () -> Dataset.readAll(p));
        assertTrue(e.getMessage().startsWith("dataset linha 2:"), e.getMessage());
    }

    @Test
    void rejectsMissingFieldAndExcessDecimals(@TempDir Path dir) throws Exception {
        Path p = dir.resolve("tx.ndjson");
        Files.writeString(p, "{\"tx_id\":1,\"amount\":1.00}\n");
        assertThrows(UsageException.class, () -> Dataset.readAll(p));
        Files.writeString(p, "{\"tx_id\":1,\"account_id\":1,\"amount\":1.001}\n");
        assertThrows(UsageException.class, () -> Dataset.readAll(p));
    }
}
