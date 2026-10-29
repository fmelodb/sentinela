package sentinela.domain;

import static sentinela.domain.Unit.DAY;
import static sentinela.domain.Unit.HOUR;
import static sentinela.domain.Unit.MINUTE;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * As 19 janelas da seção 3 da spec funcional e a regra única dos limites. Janelas deslizantes:
 * now − span < ts ≤ now, ao microssegundo. Janelas de dias: n − 1 dias civis fechados em FS_ZONE mais o
 * dia corrente até now. WINDOWS, bounds e os métodos de fatia são do modelo antigo, em remoção.
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

    public static final List<RollingWindow> ROLLING = List.of(
            new RollingWindow("1m", Duration.ofMinutes(1)),
            new RollingWindow("5m", Duration.ofMinutes(5)),
            new RollingWindow("10m", Duration.ofMinutes(10)),
            new RollingWindow("15m", Duration.ofMinutes(15)),
            new RollingWindow("30m", Duration.ofMinutes(30)),
            new RollingWindow("1h", Duration.ofHours(1)),
            new RollingWindow("2h", Duration.ofHours(2)),
            new RollingWindow("3h", Duration.ofHours(3)),
            new RollingWindow("5h", Duration.ofHours(5)),
            new RollingWindow("6h", Duration.ofHours(6)),
            new RollingWindow("9h", Duration.ofHours(9)),
            new RollingWindow("12h", Duration.ofHours(12)),
            new RollingWindow("24h", Duration.ofHours(24)),
            new RollingWindow("48h", Duration.ofHours(48)),
            new RollingWindow("72h", Duration.ofHours(72)));

    public static final List<DayWindow> DAYS = List.of(
            new DayWindow("7d", 7),
            new DayWindow("31d", 31),
            new DayWindow("62d", 62),
            new DayWindow("92d", 92));

    public static final List<String> SUFFIXES = Stream.concat(
            ROLLING.stream().map(RollingWindow::suffix), DAYS.stream().map(DayWindow::suffix)).toList();

    public static final int SIZE = SUFFIXES.size();
    public static final int FIRST_DAY_WINDOW = ROLLING.size();

    private WindowCatalog() {}

    public static Instant dayStart(long epochDay, ZoneId zone) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant();
    }

    public static Limits limits(Instant now, ZoneId zone) {
        Instant n = now.truncatedTo(ChronoUnit.MICROS);
        long today = epochDay(n, zone);
        return new Limits(n, ROLLING.stream().map(w -> n.minus(w.span())).toList(), today, dayStart(today, zone),
                DAYS.stream().map(w -> today - (w.n() - 1)).toList());
    }

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
