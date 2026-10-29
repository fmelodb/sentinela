package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import sentinela.domain.DaySlice;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;

class SliceCodecTest {
    private static final Instant TS = Instant.parse("2026-10-29T20:23:40.123456Z");

    @Test
    void formatsKeysAndFields() {
        assertEquals("fs:{4711}", SliceCodec.key(4711));
        assertEquals("fs:{4711}:tx", SliceCodec.txKey(4711));
        assertEquals("d:20754", SliceCodec.dayField(20754));
    }

    @Test
    void microsRoundTrip() {
        assertEquals(1_793_305_420_123_456L, SliceCodec.micros(TS));
        assertEquals(TS, SliceCodec.instant(1_793_305_420_123_456L));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), SliceCodec.instant(-1));
    }

    @Test
    void encodesAndDecodesMembers() {
        Transaction tx = new Transaction(98231, 4711, TS, 45000);
        assertEquals("98231|45000", SliceCodec.member(tx));
        assertEquals(tx, SliceCodec.decodeMember(4711, "98231|45000", 1.793305420123456E15));
    }

    @Test
    void encodesAndDecodesDaySlices() {
        WindowAgg a = new WindowAgg(3, 45000, 2000L, 30000L);
        assertEquals("3|45000|2000|30000", SliceCodec.encode(a));
        assertEquals(new DaySlice(20754, a), SliceCodec.decodeDay("d:20754", "3|45000|2000|30000"));
        assertEquals(new DaySlice(20733, WindowAgg.of(7)), SliceCodec.decodeDay("d:20733", "1|7|7|7"));
    }

    @Test
    void rejectsMalformedDayFieldOrValue() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("m:1", "1|1|1|1"), "fatia de minuto antiga");
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("h:1", "1|1|1|1"), "fatia de hora antiga");
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d1", "1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1|1|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeDay("d:1", "1|1.5|1|1"));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.encode(WindowAgg.EMPTY));
    }

    @Test
    void rejectsMalformedMember() {
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "98231", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "98231|1|2", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "a|1", 1e15));
        assertThrows(IllegalArgumentException.class, () -> SliceCodec.decodeMember(1, "1|1", 1.5));
    }
}
