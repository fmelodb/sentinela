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
import sentinela.domain.Limits;
import sentinela.domain.Transaction;
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
        Instant hourAgo = now.minus(1, ChronoUnit.HOURS);
        Instant todayStart = WindowCatalog.dayStart(WindowCatalog.epochDay(now, zone), zone);
        List<Instant> boundaries = boundaries(now, zone);
        List<Transaction> out = new ArrayList<>();
        long nextId = 1;
        for (long acc = 1; acc <= accounts; acc++) {
            List<Instant> times = new ArrayList<>();
            switch (accountClass(acc)) {
                case NORMAL -> addUniform(times, rnd, rnd.nextInt(301), start, now, true);
                case SILENT_LAST_HOUR -> addUniform(times, rnd, rnd.nextInt(301), start, hourAgo, true);
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

    /**
     * Os dois lados de cada limite da seção 3: now − span (fora) e 1 µs depois (dentro); now (dentro) e
     * 1 µs depois (futuro, fora); 00:00 local de hoje e de cada primeiro dia fechado, e 1 µs antes; 00:00 UTC.
     */
    public static List<Instant> boundaries(Instant now, ZoneId zone) {
        Limits l = WindowCatalog.limits(now, zone);
        Set<Instant> s = new LinkedHashSet<>();
        for (Instant lo : l.rollingLo()) {
            s.add(lo);
            s.add(lo.plusNanos(1_000));
        }
        s.add(l.now());
        s.add(l.now().plusNanos(1_000));
        List<Instant> midnights = new ArrayList<>();
        midnights.add(l.todayStart());
        l.closedLo().forEach(d -> midnights.add(WindowCatalog.dayStart(d, zone)));
        midnights.add(now.truncatedTo(ChronoUnit.DAYS));
        for (Instant t : midnights) {
            s.add(t);
            s.add(t.minusNanos(1_000));
        }
        return List.copyOf(s);
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
