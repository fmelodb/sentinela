package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class WindowAggregatorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo
    private static final Limits L = WindowCatalog.limits(NOW, SP);
    private static final long T = L.today();

    private static long ids;

    private static Transaction tx(Instant ts, long cents) {
        return new Transaction(++ids, 1, ts, cents);
    }

    private static FeatureVector agg(List<Transaction> txs, List<DaySlice> days) {
        return WindowAggregator.aggregate(txs, days, L);
    }

    @Test
    void nothingGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), agg(List.of(), List.of()));
    }

    @Test
    void lowerBoundIsExclusiveAndUpperInclusive() {
        Instant lo5m = NOW.minus(5, ChronoUnit.MINUTES);
        FeatureVector v = agg(List.of(tx(lo5m, 100), tx(lo5m.plusNanos(1_000), 200), tx(NOW, 300)), List.of());
        assertEquals(WindowAgg.of(300), v.agg(0), "1m: só now");
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m: exatamente now − 5m fica fora");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void twoHoursCoverExactlyTwoHours() {
        FeatureVector v = agg(List.of(tx(NOW.minus(Duration.ofHours(2)).plusSeconds(1), 100),
                tx(NOW.minus(Duration.ofHours(2)).minusSeconds(1), 200)), List.of());
        assertEquals(WindowAgg.of(100), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 300, 100L, 200L), v.agg(7), "3h");
    }

    @Test
    void futureIsIgnored() {
        Instant future = NOW.plusNanos(1_000);
        FeatureVector v = agg(List.of(tx(future, 100)), List.of(new DaySlice(T + 1, WindowAgg.of(100))));
        assertEquals(FeatureVector.empty(), v);
    }

    @Test
    void dayWindowsAddTodayToClosedDays() {
        Instant tenAm = L.todayStart().plus(10, ChronoUnit.HOURS);
        FeatureVector v = agg(List.of(tx(tenAm, 1000), tx(L.todayStart(), 10), tx(L.todayStart().minusNanos(1_000), 7)),
                List.of(new DaySlice(T - 1, new WindowAgg(2, 500, 100L, 400L)),
                        new DaySlice(T - 6, WindowAgg.of(50)),
                        new DaySlice(T - 7, WindowAgg.of(60))));
        assertEquals(new WindowAgg(5, 1560, 10L, 1000L), v.agg(15), "7d: T−6..T−1 e hoje desde 00:00");
        assertEquals(new WindowAgg(6, 1620, 10L, 1000L), v.agg(16), "31d inclui T−7");
        assertEquals(new WindowAgg(3, 1017, 7L, 1000L), v.agg(12), "24h vem das transações, inclusive a de ontem");
    }

    @Test
    void daySlicesOutsideClosedRangeAreIgnored() {
        FeatureVector v = agg(List.of(), List.of(new DaySlice(T, WindowAgg.of(100)),
                new DaySlice(T - 91, WindowAgg.of(200)), new DaySlice(T - 92, WindowAgg.of(300))));
        assertEquals(WindowAgg.EMPTY, v.agg(15), "fatia de hoje não conta: hoje vem das transações");
        assertEquals(WindowAgg.of(200), v.agg(18), "92d vai até T−91");
    }

    @Test
    void manyTransactionsMatchDirectSums() {
        SplittableRandom rnd = new SplittableRandom(7);
        List<Transaction> txs = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            txs.add(tx(NOW.minus(rnd.nextLong(Duration.ofHours(80).toNanos() / 1_000), ChronoUnit.MICROS),
                    1 + rnd.nextLong(1_000_000)));
        }
        FeatureVector v = agg(txs, List.of());
        for (int i = 0; i < WindowCatalog.ROLLING.size(); i++) {
            int w = i;
            WindowAgg expected = txs.stream().filter(t -> L.inRolling(w, t.ts()))
                    .map(t -> WindowAgg.of(t.amountCents())).reduce(WindowAgg.EMPTY, WindowAgg::plus);
            assertEquals(expected, v.agg(i), WindowCatalog.SUFFIXES.get(i));
        }
    }
}
