package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sentinela.domain.Slice;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;

class SliceCodecTest {
    @Test
    void formatsKeyAndFields() {
        assertEquals("fs:{4711}", SliceCodec.key(4711));
        assertEquals("m:29856720", SliceCodec.field(Unit.MINUTE, 29856720));
        assertEquals("h:497612", SliceCodec.field(Unit.HOUR, 497612));
        assertEquals("d:20733", SliceCodec.field(Unit.DAY, 20733));
    }

    @Test
    void encodesAndDecodesCents() {
        WindowAgg a = new WindowAgg(3, 45000, 2000L, 30000L);
        assertEquals("3|45000|2000|30000", SliceCodec.encode(a));
        assertEquals(new Slice(Unit.HOUR, 497612, a), SliceCodec.decode("h:497612", "3|45000|2000|30000"));
        assertEquals(new Slice(Unit.DAY, 20733, WindowAgg.of(7)), SliceCodec.decode("d:20733", "1|7|7|7"));
    }

    @Test
    void rejectsMalformedFieldOrValue() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("x:1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decode("m:1", "1|1.5|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.encode(WindowAgg.EMPTY));
    }
}
