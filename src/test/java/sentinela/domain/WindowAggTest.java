package sentinela.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WindowAggTest {
    @Test
    void emptyIsNeutral() {
        WindowAgg a = WindowAgg.of(500);
        assertEquals(a, WindowAgg.EMPTY.plus(a));
        assertEquals(a, a.plus(WindowAgg.EMPTY));
        assertEquals(WindowAgg.EMPTY, WindowAgg.EMPTY.plus(WindowAgg.EMPTY));
    }

    @Test
    void combinesCountSumMinMax() {
        WindowAgg r = WindowAgg.of(2000).plus(WindowAgg.of(30000)).plus(WindowAgg.of(13000));
        assertEquals(new WindowAgg(3, 45000, 2000L, 30000L), r);
    }

    @Test
    void rejectsInconsistentEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(0, 10, null, null));
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(0, 0, 1L, 1L));
    }

    @Test
    void rejectsNonEmptyWithoutMinMax() {
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(1, 10, null, 10L));
        assertThrows(IllegalArgumentException.class, () -> new WindowAgg(-1, 0, null, null));
    }
}
