package sentinela.adapter.valkey;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.DaySlice;
import sentinela.domain.FeatureVector;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

/** Destrutivo: FLUSHDB no Valkey. */
@Tag("integration")
class ValkeyMaintenanceIT {
    private static Env env;
    private static ValkeyConnections conns;
    private static StatefulRedisConnection<String, String> bulk;
    private static ValkeyMaintenance vm;
    private static ValkeyFeatureStore store;
    private static RedisCommands<String, String> raw;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.valkeyUrl() != null, "VALKEY_URL ausente");
        sentinela.testsupport.DestructiveGuard.assumeAllowed(env);
        conns = ValkeyConnections.open(env);
        bulk = conns.openBulk();
        LuaScripts scripts = new LuaScripts();
        vm = new ValkeyMaintenance(bulk, scripts);
        store = new ValkeyFeatureStore(conns.hot(), scripts, env.zone());
        raw = conns.hot().sync();
    }

    @AfterAll
    static void tearDown() {
        if (conns != null) {
            bulk.close();
            conns.close();
        }
    }

    @BeforeEach
    void reset() {
        vm.reset();
    }

    @Test
    void resetEmpties() {
        raw.hset("fs:{1}", "d:1", "1|1|1|1");
        assertFalse(vm.isEmpty());
        vm.reset();
        assertTrue(vm.isEmpty());
    }

    @Test
    void putDaysWritesOverwritesAndDropsOldDays() {
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        raw.hset("fs:{1}", "d:" + (today - 100), "1|1|1|1");
        List<AccountAgg> rows = List.of(new AccountAgg(1, new WindowAgg(2, 500, 100L, 400L)),
                new AccountAgg(2, WindowAgg.of(50)));
        assertEquals(2, vm.putDays(today - 1, rows, today));
        assertEquals("2|500|100|400", raw.hget("fs:{1}", "d:" + (today - 1)));
        assertEquals("1|50|50|50", raw.hget("fs:{2}", "d:" + (today - 1)));
        assertFalse(raw.hexists("fs:{1}", "d:" + (today - 100)), "dia fora da retenção removido");

        vm.putDays(today - 1, List.of(new AccountAgg(1, WindowAgg.of(9))), today);
        assertEquals("1|9|9|9", raw.hget("fs:{1}", "d:" + (today - 1)), "sobrescreve: idempotente");
        assertEquals(0, vm.putDays(today - 92, rows, today), "92 dias atrás já está fora da 92d");
        assertEquals(2, vm.putDays(today - 91, rows, today), "91 dias atrás é o primeiro dia da 92d");
    }

    @Test
    void putDaysReloadsScriptsAfterFlush() {
        raw.scriptFlush();
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        assertEquals(1, vm.putDays(today - 1, List.of(new AccountAgg(1, WindowAgg.of(9))), today));
    }

    @Test
    void sliceWriterRebuildsReadableStore() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant ts = now.minusSeconds(10);
        long today = WindowCatalog.epochDay(now, env.zone());
        WindowAgg closed = new WindowAgg(3, 900, 100L, 500L);
        try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
            for (long acc = 1; acc <= 2_500; acc++) {
                w.writeTx(new Transaction(acc, acc, ts, 100));
                w.writeDay(new KeyedSlice(acc, new DaySlice(today - 1, closed)));
            }
            w.close();
            assertEquals(5_000, w.written());
        }
        FeatureVector v = store.read(2_500, now);
        assertEquals(WindowAgg.of(100), v.agg(0));
        assertEquals(WindowAgg.of(100), v.agg(6));
        WindowAgg expected7d = WindowCatalog.epochDay(ts, env.zone()) == today ? closed.plus(WindowAgg.of(100)) : closed;
        assertEquals(expected7d, v.agg(15));
        assertEquals(5_000, raw.dbsize(), "duas chaves por conta");
    }
}
