package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransactionTest {
    @Test
    void acceptsMicrosecondPrecision() {
        Instant ts = Instant.parse("2026-10-29T12:00:00.123456Z");
        assertEquals(ts, new Transaction(1, 7, ts, 100).ts());
    }

    @Test
    void rejectsSubMicrosecondPrecision() {
        Instant ts = Instant.parse("2026-10-29T12:00:00.123456789Z");
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 7, ts, 100));
    }

    @Test
    void rejectsNonPositiveAmountAndAccount() {
        Instant ts = Instant.parse("2026-10-29T12:00:00Z");
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 7, ts, 0));
        assertThrows(IllegalArgumentException.class, () -> new Transaction(1, 0, ts, 10));
    }
}
