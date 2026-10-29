package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BoundsTest {
    @Test
    void containsIsInclusiveAndUnitSensitive() {
        Bounds b = new Bounds(Unit.MINUTE, 10, 14);
        assertTrue(b.contains(Unit.MINUTE, 10));
        assertTrue(b.contains(Unit.MINUTE, 14));
        assertFalse(b.contains(Unit.MINUTE, 9));
        assertFalse(b.contains(Unit.MINUTE, 15));
        assertFalse(b.contains(Unit.HOUR, 12));
    }
}
