package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

@Tag("integration")
class ValkeyFeatureStoreIT {
    private static final long ACC = 990_000_002L;
    private static ValkeyConnections conns;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        Env env = Env.fromSystem();
        assumeTrue(env.valkeyUrl() != null, "VALKEY_URL ausente");
        conns = ValkeyConnections.open(env);
        store = new ValkeyFeatureStore(conns.hot(), new LuaScripts(), env.zone());
        raw = conns.hot().sync();
    }

    @AfterAll
    static void tearDown() {
        if (conns != null) {
            conns.close();
        }
    }

    @BeforeEach
    void clean() {
        raw.del(SliceCodec.key(ACC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, now()));
    }

    @Test
    void ingestCombinesMinuteAndHourSlices() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minusSeconds(5), 100));
        store.ingest(new Transaction(2, ACC, now, 200));
        String key = SliceCodec.key(ACC);
        assertEquals("2|300|100|200", raw.hget(key, SliceCodec.field(Unit.MINUTE, WindowCatalog.epochMinute(now))));
        assertEquals("2|300|100|200", raw.hget(key, SliceCodec.field(Unit.HOUR, WindowCatalog.epochHour(now))));
    }

    @Test
    void readMatchesWindowDefinition() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        store.ingest(new Transaction(2, ACC, now.minus(2, ChronoUnit.HOURS), 2000));
        store.ingest(new Transaction(3, ACC, now.minusSeconds(10), 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
    }

    @Test
    void newMinuteRemovesFieldsOutsideRetention() {
        Instant now = now();
        long m = WindowCatalog.epochMinute(now);
        long h = WindowCatalog.epochHour(now);
        store.ingest(new Transaction(1, ACC, now.minus(80, ChronoUnit.HOURS), 100));
        store.ingest(new Transaction(2, ACC, now.minus(100, ChronoUnit.MINUTES), 100));
        store.ingest(new Transaction(3, ACC, now, 100));
        String key = SliceCodec.key(ACC);
        assertFalse(raw.hexists(key, SliceCodec.field(Unit.MINUTE, m - 100)), "minuto fora de 60");
        assertFalse(raw.hexists(key, SliceCodec.field(Unit.HOUR, h - 80)), "hora fora de 72");
        assertTrue(raw.hexists(key, SliceCodec.field(Unit.MINUTE, m)));
        assertTrue(raw.hexists(key, SliceCodec.field(Unit.HOUR, WindowCatalog.epochHour(now.minus(100, ChronoUnit.MINUTES)))),
                "hora de 100 minutos atrás continua dentro de 72h");
    }

    @Test
    void reloadsScriptAfterScriptFlush() {
        raw.scriptFlush();
        store.ingest(new Transaction(1, ACC, now(), 100));
        assertEquals(1, store.read(ACC, now()).agg(0).cnt());
    }
}
