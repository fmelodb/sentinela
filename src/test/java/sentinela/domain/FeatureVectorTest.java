package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureVectorTest {
    @Test
    void namesFollowWindowAndMetricOrder() {
        List<String> n = FeatureVector.names();
        assertEquals(95, n.size());
        assertEquals(List.of("cnt_1m", "sum_1m", "min_1m", "max_1m", "avg_1m"), n.subList(0, 5));
        assertEquals("cnt_1h", n.get(25));
        assertEquals("cnt_7d", n.get(75));
        assertEquals("avg_92d", n.get(94));
    }

    @Test
    void emptyVectorHasZeroCountAndSumAndNullOthers() {
        List<BigDecimal> v = FeatureVector.empty().values();
        assertEquals(95, v.size());
        for (int w = 0; w < 19; w++) {
            assertEquals(0, v.get(w * 5).compareTo(BigDecimal.ZERO), "cnt");
            assertEquals(0, v.get(w * 5 + 1).compareTo(BigDecimal.ZERO), "sum");
            assertNull(v.get(w * 5 + 2), "min");
            assertNull(v.get(w * 5 + 3), "max");
            assertNull(v.get(w * 5 + 4), "avg");
        }
    }

    @Test
    void valuesConvertCentsAndComputeAverage() {
        List<BigDecimal> v = withFirst(new WindowAgg(3, 45000, 2000L, 30000L)).values();
        assertEquals(new BigDecimal("3"), v.get(0));
        assertEquals(new BigDecimal("450.00"), v.get(1));
        assertEquals(new BigDecimal("20.00"), v.get(2));
        assertEquals(new BigDecimal("300.00"), v.get(3));
        assertEquals(new BigDecimal("150.0000"), v.get(4));
    }

    @Test
    void averageUsesHalfEvenAtScaleFour() {
        // 1 centavo / 8 = R$ 0,00125 → HALF_EVEN dá 0,0012 (HALF_UP daria 0,0013)
        assertEquals(new BigDecimal("0.0012"), withFirst(new WindowAgg(8, 1, 1L, 1L)).values().get(4));
    }

    @Test
    void rejectsWrongSize() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureVector(List.of(WindowAgg.EMPTY)));
    }

    @Test
    void equalityComparesAggregates() {
        assertEquals(withFirst(WindowAgg.of(10)), withFirst(WindowAgg.of(10)));
        assertNotEquals(withFirst(WindowAgg.of(10)), withFirst(WindowAgg.of(11)));
    }

    private static FeatureVector withFirst(WindowAgg first) {
        List<WindowAgg> aggs = new ArrayList<>(Collections.nCopies(19, WindowAgg.EMPTY));
        aggs.set(0, first);
        return new FeatureVector(aggs);
    }
}
