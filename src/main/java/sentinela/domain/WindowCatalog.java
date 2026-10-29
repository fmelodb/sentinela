package sentinela.domain;

import static sentinela.domain.Unit.DAY;
import static sentinela.domain.Unit.HOUR;
import static sentinela.domain.Unit.MINUTE;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * As 19 janelas da seção 3 da spec funcional e a regra única de alinhamento.
 * Minutos e horas: fatia corrente e as n-1 anteriores, em epoch UTC.
 * Dias: os n dias civis fechados antes de hoje, em FS_ZONE.
 */
public final class WindowCatalog {
    public static final List<Window> WINDOWS = List.of(
            new Window("1m", MINUTE, 1),
            new Window("5m", MINUTE, 5),
            new Window("10m", MINUTE, 10),
            new Window("15m", MINUTE, 15),
            new Window("30m", MINUTE, 30),
            new Window("1h", MINUTE, 60),
            new Window("2h", HOUR, 2),
            new Window("3h", HOUR, 3),
            new Window("5h", HOUR, 5),
            new Window("6h", HOUR, 6),
            new Window("9h", HOUR, 9),
            new Window("12h", HOUR, 12),
            new Window("24h", HOUR, 24),
            new Window("48h", HOUR, 48),
            new Window("72h", HOUR, 72),
            new Window("7d", DAY, 7),
            new Window("31d", DAY, 31),
            new Window("62d", DAY, 62),
            new Window("92d", DAY, 92));

    public static final int SIZE = WINDOWS.size();
    public static final int FIRST_DAY_WINDOW = 15;

    private WindowCatalog() {}

    public static long epochMinute(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 60);
    }

    public static long epochHour(Instant t) {
        return Math.floorDiv(t.getEpochSecond(), 3600);
    }

    public static long epochDay(Instant t, ZoneId zone) {
        return LocalDate.ofInstant(t, zone).toEpochDay();
    }

    public static Instant sliceStart(Unit unit, long index, ZoneId zone) {
        return switch (unit) {
            case MINUTE -> Instant.ofEpochSecond(index * 60);
            case HOUR -> Instant.ofEpochSecond(index * 3600);
            case DAY -> LocalDate.ofEpochDay(index).atStartOfDay(zone).toInstant();
        };
    }

    public static List<Bounds> bounds(Instant now, ZoneId zone) {
        long m = epochMinute(now);
        long h = epochHour(now);
        long d = epochDay(now, zone);
        return WINDOWS.stream().map(w -> switch (w.unit()) {
            case MINUTE -> new Bounds(MINUTE, m - (w.n() - 1), m);
            case HOUR -> new Bounds(HOUR, h - (w.n() - 1), h);
            case DAY -> new Bounds(DAY, d - w.n(), d - 1);
        }).toList();
    }
}
