package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowCatalogTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo

    @Test
    void suffixesFollowSpecOrder() {
        assertEquals(List.of("1m", "5m", "10m", "15m", "30m", "1h", "2h", "3h", "5h", "6h", "9h", "12h",
                "24h", "48h", "72h", "7d", "31d", "62d", "92d"), WindowCatalog.SUFFIXES);
        assertEquals(15, WindowCatalog.ROLLING.size());
        assertEquals(4, WindowCatalog.DAYS.size());
        assertEquals("7d", WindowCatalog.SUFFIXES.get(WindowCatalog.FIRST_DAY_WINDOW));
        assertEquals(new RollingWindow("1h", Duration.ofHours(1)), WindowCatalog.ROLLING.get(5));
        assertEquals(new DayWindow("92d", 92), WindowCatalog.DAYS.get(3));
    }

    @Test
    void rollingLimitsAreExactlySpanBeforeNow() {
        Limits l = WindowCatalog.limits(NOW, SP);
        assertEquals(NOW, l.now());
        assertEquals(Instant.parse("2026-10-29T20:22:40.123456Z"), l.rollingLo().get(0), "1m");
        assertEquals(Instant.parse("2026-10-29T20:18:40.123456Z"), l.rollingLo().get(1), "5m");
        assertEquals(Instant.parse("2026-10-29T19:23:40.123456Z"), l.rollingLo().get(5), "1h");
        assertEquals(Instant.parse("2026-10-29T18:23:40.123456Z"), l.rollingLo().get(6), "2h");
        assertEquals(Instant.parse("2026-10-28T20:23:40.123456Z"), l.rollingLo().get(12), "24h");
        assertEquals(Instant.parse("2026-10-26T20:23:40.123456Z"), l.rollingLo().get(14), "72h");
        assertEquals(l.rollingLo().get(14), l.oldestRolling());
    }

    @Test
    void dayLimitsAreNMinusOneClosedDaysPlusToday() {
        Limits l = WindowCatalog.limits(NOW, SP);
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), l.today());
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"), l.todayStart());
        assertEquals(List.of(LocalDate.of(2026, 10, 23).toEpochDay(), LocalDate.of(2026, 9, 29).toEpochDay(),
                LocalDate.of(2026, 8, 29).toEpochDay(), LocalDate.of(2026, 7, 30).toEpochDay()), l.closedLo());
        assertEquals(LocalDate.of(2026, 7, 30).toEpochDay(), l.oldestClosed());
    }

    @Test
    void limitsTruncateNowToMicros() {
        Limits l = WindowCatalog.limits(Instant.parse("2026-10-29T20:23:40.123456789Z"), SP);
        assertEquals(NOW, l.now());
        assertEquals(Instant.parse("2026-10-29T20:18:40.123456Z"), l.rollingLo().get(1));
    }

    @Test
    void todayStartsAtLocalMidnight() {
        Instant midnight = Instant.parse("2026-10-29T03:00:00Z");
        Limits l = WindowCatalog.limits(midnight, SP);
        assertEquals(midnight, l.todayStart());
        assertTrue(l.inToday(midnight));
        assertFalse(l.inToday(midnight.minusNanos(1_000)), "23:59:59.999999 de ontem");
        assertEquals(LocalDate.of(2026, 10, 28).toEpochDay(),
                WindowCatalog.limits(midnight.minusNanos(1_000), SP).today());
    }

    @Test
    void dayStartIsLocalMidnightInUtc() {
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"),
                WindowCatalog.dayStart(LocalDate.of(2026, 10, 29).toEpochDay(), SP));
    }

    @Test
    void civilDayFollowsZoneNotUtc() {
        Instant now = Instant.parse("2026-10-30T01:30:00Z"); // 22:30 de 29/10 em São Paulo
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), WindowCatalog.epochDay(now, SP));
        assertEquals(LocalDate.of(2026, 10, 30).toEpochDay(), WindowCatalog.epochDay(now, ZoneOffset.UTC));
    }
}
