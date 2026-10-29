package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

@Tag("integration")
class ValkeyFeatureStoreIT {
    private static final long ACC = 990_000_002L;
    private static Env env;
    private static ValkeyConnections conns;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
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
        raw.del(SliceCodec.key(ACC), SliceCodec.txKey(ACC));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, now()));
    }

    @Test
    void ingestKeepsOneMemberPerTxIdAndTheFirstTs() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minusSeconds(5), 100));
        store.ingest(new Transaction(2, ACC, now, 200));
        store.ingest(new Transaction(1, ACC, now, 100)); // reentrega com outro ts
        String key = SliceCodec.txKey(ACC);
        assertEquals(2, raw.zcard(key));
        assertEquals((double) SliceCodec.micros(now.minusSeconds(5)), raw.zscore(key, "1|100"));
    }

    @Test
    void readMatchesWindowDefinition() {
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        store.ingest(new Transaction(2, ACC, now.minus(2, ChronoUnit.HOURS).plusSeconds(1), 2000));
        store.ingest(new Transaction(3, ACC, now.minusSeconds(10), 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(6), "2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
    }

    @Test
    void rollingLowerBoundIsExclusiveAndUpperInclusive() {
        Instant now = now();
        Instant lo5m = now.minus(5, ChronoUnit.MINUTES);
        store.ingest(new Transaction(11, ACC, lo5m, 100));
        store.ingest(new Transaction(12, ACC, lo5m.plusNanos(1_000), 200));
        store.ingest(new Transaction(13, ACC, now, 300));
        store.ingest(new Transaction(14, ACC, now.plusNanos(1_000), 400));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void dayWindowsCombineDaySlicesAndToday() {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        Instant todayStart = WindowCatalog.dayStart(today, env.zone());
        raw.hset(SliceCodec.key(ACC), SliceCodec.dayField(today - 1), "2|500|100|400");
        raw.hset(SliceCodec.key(ACC), SliceCodec.dayField(today - 7), "1|50|50|50");
        store.ingest(new Transaction(21, ACC, todayStart.minusNanos(1_000), 7));
        store.ingest(new Transaction(22, ACC, todayStart, 1000));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(3, 1500, 100L, 1000L), v.agg(15), "7d");
        assertEquals(new WindowAgg(4, 1550, 50L, 1000L), v.agg(16), "31d");
    }

    @Test
    void ingestDropsTransactionsOlderThanRetention() {
        Instant now = now();
        store.ingest(new Transaction(31, ACC, now.minus(80, ChronoUnit.HOURS), 100));
        store.ingest(new Transaction(32, ACC, now.minus(72, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES), 100));
        store.ingest(new Transaction(33, ACC, now, 100));
        assertEquals(2, raw.zcard(SliceCodec.txKey(ACC)), "80h sai; 72h30 fica na folga de 73h");
        assertEquals(1, store.read(ACC, now).agg(14).cnt(), "72h conta só a de agora");
    }

    @Test
    void futureTransactionKeepsRetentionMargin() {
        Instant now = now();
        store.ingest(new Transaction(41, ACC, now.minus(Duration.ofHours(72)).plusNanos(1_000), 100));
        store.ingest(new Transaction(42, ACC, now.plusSeconds(30), 100)); // ingestor adiantado
        assertEquals(WindowAgg.of(100), store.read(ACC, now).agg(14), "72h ainda vê a transação da borda");
    }

    @Test
    void reloadsScriptAfterScriptFlush() {
        raw.scriptFlush();
        Instant now = now();
        store.ingest(new Transaction(1, ACC, now, 100));
        assertEquals(1, store.read(ACC, now).agg(0).cnt());
    }
}
