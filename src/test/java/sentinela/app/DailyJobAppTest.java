package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;

class DailyJobAppTest {
    private static final long TODAY = LocalDate.of(2026, 10, 29).toEpochDay();

    @Test
    void defaultsToYesterday() {
        assertEquals(TODAY - 1, DailyJobApp.parseDay(Optional.empty(), TODAY));
    }

    @Test
    void parsesIsoDate() {
        assertEquals(LocalDate.of(2026, 10, 20).toEpochDay(), DailyJobApp.parseDay(Optional.of("2026-10-20"), TODAY));
    }

    @Test
    void rejectsTodayFutureAndGarbage() {
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("2026-10-29"), TODAY));
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("2026-11-01"), TODAY));
        assertThrows(UsageException.class, () -> DailyJobApp.parseDay(Optional.of("29/10/2026"), TODAY));
    }
}
