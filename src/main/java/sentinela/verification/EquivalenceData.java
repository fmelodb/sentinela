package sentinela.verification;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import sentinela.domain.Bounds;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AmountSampler;
import sentinela.workload.Seeds;

/** Sequência do cenário C: 93 dias de transações em quatro classes de conta (conta mod 4). */
public final class EquivalenceData {
    public static final int NORMAL = 0;
    public static final int SILENT_LAST_HOUR = 1;
    public static final int TODAY_ONLY = 2;
    public static final int BOUNDARIES = 3;
    private static final int HISTORY_DAYS = 93;

    private EquivalenceData() {}

    public static int accountClass(long accountId) {
        return (int) (accountId % 4);
    }

    public static List<Transaction> generate(int accounts, long seed, Instant now, ZoneId zone) {
        SplittableRandom rnd = Seeds.stream(seed, "equivalencia");
        AmountSampler amounts = new AmountSampler(rnd);
        Instant start = now.minus(HISTORY_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        Instant lastHourStart = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(now) - 59, zone);
        Instant todayStart = WindowCatalog.sliceStart(Unit.DAY, WindowCatalog.epochDay(now, zone), zone);
        List<Instant> boundaries = boundaries(now, zone);
        List<Transaction> out = new ArrayList<>();
        long nextId = 1;
        for (long acc = 1; acc <= accounts; acc++) {
            List<Instant> times = new ArrayList<>();
            switch (accountClass(acc)) {
                case NORMAL -> addUniform(times, rnd, rnd.nextInt(301), start, now, true);
                case SILENT_LAST_HOUR -> addUniform(times, rnd, rnd.nextInt(301), start, lastHourStart, false);
                case TODAY_ONLY -> addUniform(times, rnd, 1 + rnd.nextInt(20), todayStart, now, true);
                default -> times.addAll(boundaries);
            }
            for (Instant ts : times) {
                out.add(new Transaction(nextId++, acc, ts, amounts.nextCents()));
            }
        }
        out.sort(Comparator.comparing(Transaction::ts).thenComparingLong(Transaction::txId));
        return out;
    }

    /** Limite inferior de cada janela e 1 µs antes, mais 00:00 local e 00:00 UTC de hoje e 1 µs antes. */
    public static List<Instant> boundaries(Instant now, ZoneId zone) {
        Set<Instant> s = new LinkedHashSet<>();
        for (Bounds b : WindowCatalog.bounds(now, zone)) {
            Instant lower = WindowCatalog.sliceStart(b.unit(), b.lo(), zone);
            s.add(lower);
            s.add(lower.minusNanos(1_000));
        }
        Instant localMidnight = WindowCatalog.sliceStart(Unit.DAY, WindowCatalog.epochDay(now, zone), zone);
        Instant utcMidnight = now.truncatedTo(ChronoUnit.DAYS);
        for (Instant t : List.of(localMidnight, utcMidnight)) {
            s.add(t);
            s.add(t.minusNanos(1_000));
        }
        return s.stream().filter(t -> !t.isAfter(now)).toList();
    }

    private static void addUniform(List<Instant> times, SplittableRandom rnd, int n, Instant from, Instant to,
            boolean inclusive) {
        long span = Duration.between(from, to).toNanos() / 1_000 + (inclusive ? 1 : 0);
        if (span <= 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            times.add(from.plus(rnd.nextLong(span), ChronoUnit.MICROS));
        }
    }
}
