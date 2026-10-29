package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.WindowAgg;

class EquivalenceCheckTest {
    @Test
    void diffNamesEachDivergentPosition() {
        List<WindowAgg> aggs = new ArrayList<>(Collections.nCopies(19, WindowAgg.EMPTY));
        aggs.set(1, WindowAgg.of(100));
        FeatureVector b = new FeatureVector(aggs);
        List<String> d = EquivalenceCheck.diff(7, "oracle", FeatureVector.empty(), "valkey", b);
        assertEquals(5, d.size(), "cnt, sum, min, max e avg de 5m");
        assertEquals("conta 7 cnt_5m: oracle=0 valkey=1", d.get(0));
        assertTrue(d.get(2).startsWith("conta 7 min_5m: oracle=null valkey=1.00"));
    }

    @Test
    void equalVectorsHaveNoDiff() {
        assertTrue(EquivalenceCheck.diff(1, "a", FeatureVector.empty(), "b", FeatureVector.empty()).isEmpty());
    }
}
