package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowCatalogTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    void hasNineteenWindowsInSpecOrder() {
        List<String> suffixes = WindowCatalog.WINDOWS.stream().map(Window::suffix).toList();
        assertEquals(List.of("1m", "5m", "10m", "15m", "30m", "1h", "2h", "3h", "5h", "6h", "9h", "12h",
                "24h", "48h", "72h", "7d", "31d", "62d", "92d"), suffixes);
        assertEquals(19, WindowCatalog.SIZE);
        assertEquals("7d", WindowCatalog.WINDOWS.get(WindowCatalog.FIRST_DAY_WINDOW).suffix());
    }

    @Test
    void oneHourUsesSixtyMinuteSlices() {
        assertEquals(new Window("1h", Unit.MINUTE, 60), WindowCatalog.WINDOWS.get(5));
    }

    @Test
    void minuteAndHourBoundsAlignToCurrentSlice() {
        Instant now = Instant.parse("2026-10-29T15:07:30Z");
        long m = now.getEpochSecond() / 60;
        long h = now.getEpochSecond() / 3600;
        List<Bounds> b = WindowCatalog.bounds(now, SP);
        assertEquals(19, b.size());
        assertEquals(new Bounds(Unit.MINUTE, m, m), b.get(0));
        assertEquals(new Bounds(Unit.MINUTE, m - 4, m), b.get(1));
        assertEquals(new Bounds(Unit.MINUTE, m - 29, m), b.get(4));
        assertEquals(new Bounds(Unit.MINUTE, m - 59, m), b.get(5));
        assertEquals(new Bounds(Unit.HOUR, h - 1, h), b.get(6));
        assertEquals(new Bounds(Unit.HOUR, h - 23, h), b.get(12));
        assertEquals(new Bounds(Unit.HOUR, h - 71, h), b.get(14));
    }

    @Test
    void dayBoundsExcludeTodayInZone() {
        Instant now = Instant.parse("2026-10-29T15:07:30Z"); // 12:07 em São Paulo
        long today = LocalDate.of(2026, 10, 29).toEpochDay();
        List<Bounds> b = WindowCatalog.bounds(now, SP);
        assertEquals(new Bounds(Unit.DAY, today - 7, today - 1), b.get(15));
        assertEquals(new Bounds(Unit.DAY, today - 31, today - 1), b.get(16));
        assertEquals(new Bounds(Unit.DAY, today - 92, today - 1), b.get(18));
    }

    @Test
    void civilDayFollowsZoneNotUtc() {
        Instant now = Instant.parse("2026-10-30T01:30:00Z"); // 22:30 de 29/10 em São Paulo
        assertEquals(LocalDate.of(2026, 10, 29).toEpochDay(), WindowCatalog.epochDay(now, SP));
        assertEquals(LocalDate.of(2026, 10, 30).toEpochDay(), WindowCatalog.epochDay(now, ZoneOffset.UTC));
    }

    @Test
    void exactSliceBoundaryBelongsToNewSlice() {
        Instant boundary = Instant.parse("2026-10-29T15:00:00Z");
        long m = boundary.getEpochSecond() / 60;
        long h = boundary.getEpochSecond() / 3600;
        assertEquals(m, WindowCatalog.epochMinute(boundary));
        assertEquals(m - 1, WindowCatalog.epochMinute(boundary.minusNanos(1_000)));
        assertEquals(h, WindowCatalog.epochHour(boundary));
        assertEquals(h - 1, WindowCatalog.epochHour(boundary.minusNanos(1_000)));
    }

    @Test
    void negativeEpochUsesFloor() {
        assertEquals(-1, WindowCatalog.epochMinute(Instant.ofEpochSecond(-1)));
        assertEquals(-1, WindowCatalog.epochHour(Instant.ofEpochSecond(-1)));
    }

    @Test
    void sliceStartInvertsIndex() {
        Instant t = Instant.parse("2026-10-29T15:07:30Z");
        assertEquals(Instant.parse("2026-10-29T15:07:00Z"),
                WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(t), SP));
        assertEquals(Instant.parse("2026-10-29T15:00:00Z"),
                WindowCatalog.sliceStart(Unit.HOUR, WindowCatalog.epochHour(t), SP));
        assertEquals(Instant.parse("2026-10-29T03:00:00Z"),
                WindowCatalog.sliceStart(Unit.DAY, LocalDate.of(2026, 10, 29).toEpochDay(), SP));
    }
}
