package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class SliceAggregatorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");
    private static final long M = WindowCatalog.epochMinute(NOW);
    private static final long H = WindowCatalog.epochHour(NOW);
    private static final long T = WindowCatalog.epochDay(NOW, SP);

    private static FeatureVector agg(Slice... slices) {
        return SliceAggregator.aggregate(List.of(slices), WindowCatalog.bounds(NOW, SP));
    }

    @Test
    void noSlicesGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), agg());
    }

    @Test
    void currentMinuteCountsInAllMinuteWindowsAndOneHour() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M, WindowAgg.of(100)));
        for (int i = 0; i <= 5; i++) {
            assertEquals(1, v.agg(i).cnt(), "janela " + i);
        }
        assertEquals(0, v.agg(6).cnt(), "2h usa fatias de hora, não de minuto");
    }

    @Test
    void minuteSliceRespectsLowerBound() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M - 4, WindowAgg.of(100)),
                new Slice(Unit.MINUTE, M - 5, WindowAgg.of(200)));
        assertEquals(1, v.agg(1).cnt(), "5m: só M-4");
        assertEquals(2, v.agg(2).cnt(), "10m: M-4 e M-5");
        assertEquals(new WindowAgg(2, 300, 100L, 200L), v.agg(5));
    }

    @Test
    void minuteSliceOutsideOneHourIsIgnored() {
        assertEquals(FeatureVector.empty(), agg(new Slice(Unit.MINUTE, M - 60, WindowAgg.of(100))));
    }

    @Test
    void futureSlicesAreIgnored() {
        FeatureVector v = agg(new Slice(Unit.MINUTE, M + 1, WindowAgg.of(100)),
                new Slice(Unit.HOUR, H + 1, WindowAgg.of(100)),
                new Slice(Unit.DAY, T, WindowAgg.of(100)));
        assertEquals(FeatureVector.empty(), v);
    }

    @Test
    void hourSlicesFeedHourWindows() {
        FeatureVector v = agg(new Slice(Unit.HOUR, H - 2, new WindowAgg(2, 500, 100L, 400L)));
        assertEquals(0, v.agg(6).cnt(), "2h = H-1..H");
        assertEquals(2, v.agg(7).cnt(), "3h = H-2..H");
        assertEquals(2, v.agg(14).cnt(), "72h");
    }

    @Test
    void daySlicesFeedDayWindowsOnlyWhenClosed() {
        FeatureVector v = agg(new Slice(Unit.DAY, T - 1, WindowAgg.of(100)),
                new Slice(Unit.DAY, T - 8, WindowAgg.of(300)),
                new Slice(Unit.DAY, T - 93, WindowAgg.of(999)));
        assertEquals(new WindowAgg(1, 100, 100L, 100L), v.agg(15));
        assertEquals(new WindowAgg(2, 400, 100L, 300L), v.agg(16));
        assertEquals(2, v.agg(18).cnt(), "T-93 fica fora de 92d");
    }
}
