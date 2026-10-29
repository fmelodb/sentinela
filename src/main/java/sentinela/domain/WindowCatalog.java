package sentinela.domain;

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
 * dia corrente até now.
 */
public final class WindowCatalog {
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

    public static long epochDay(Instant t, ZoneId zone) {
        return LocalDate.ofInstant(t, zone).toEpochDay();
    }

    public static Instant dayStart(long epochDay, ZoneId zone) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant();
    }

    public static Limits limits(Instant now, ZoneId zone) {
        Instant n = now.truncatedTo(ChronoUnit.MICROS);
        long today = epochDay(n, zone);
        return new Limits(n, ROLLING.stream().map(w -> n.minus(w.span())).toList(), today, dayStart(today, zone),
                DAYS.stream().map(w -> today - (w.n() - 1)).toList());
    }
}
