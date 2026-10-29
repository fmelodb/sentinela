package sentinela.report;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class StatsTest {
    @Test
    void nearestRankWithoutInterpolation() {
        long[] hundred = LongStream.rangeClosed(1, 100).toArray();
        assertEquals(95, Stats.nearestRank(hundred, 950));
        assertEquals(99, Stats.nearestRank(hundred, 990));
        long[] ten = LongStream.rangeClosed(1, 10).toArray();
        assertEquals(10, Stats.nearestRank(ten, 950), "ceil(9,5) = 10");
        assertEquals(10, Stats.nearestRank(ten, 990));
        assertEquals(5, Stats.nearestRank(ten, 500));
        assertEquals(42, Stats.nearestRank(new long[] {42}, 990));
    }

    @Test
    void emptySampleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Stats.nearestRank(new long[0], 950));
    }
}
