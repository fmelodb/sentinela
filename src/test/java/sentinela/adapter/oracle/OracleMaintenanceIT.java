package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Env;

/** Destrutivo: esvazia TX e DAILY_AGG. */
@Tag("integration")
class OracleMaintenanceIT {
    private static Env env;
    private static PoolDataSource ds;
    private static OracleMaintenance m;
    private static OracleFeatureStore store;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null, "ORACLE_URL ausente");
        sentinela.testsupport.DestructiveGuard.assumeAllowed(env);
        ds = OracleDataSources.pool(env, "it-maint", 2, 1000, OracleDataSources.NO_TIMEOUT);
        m = new OracleMaintenance(ds, env.zone());
        store = new OracleFeatureStore(ds, env.zone());
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            OracleDataSources.close(ds);
        }
    }

    @BeforeEach
    void reset() {
        m.reset();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
    }

    @Test
    void resetEmptiesTables() {
        store.ingest(new Transaction(1, 1, now(), 100));
        assertFalse(m.isEmpty());
        m.reset();
        assertTrue(m.isEmpty());
        assertEquals(0, m.countTx());
    }

    @Test
    void closeDayMatchesRecalculationAndIsIdempotent() {
        Instant now = now();
        long yesterday = WindowCatalog.epochDay(now, env.zone()) - 1;
        Instant noon = WindowCatalog.sliceStart(Unit.DAY, yesterday, env.zone()).plus(12, ChronoUnit.HOURS);
        store.ingest(new Transaction(1, 10, noon, 100));
        store.ingest(new Transaction(2, 10, noon.plusSeconds(60), 300));
        store.ingest(new Transaction(3, 11, noon, 50));
        store.ingest(new Transaction(4, 10, now, 999)); // hoje: fora do dia fechado

        assertEquals(2, m.closeDay(yesterday));
        assertEquals(0, m.checkDay(yesterday));
        List<AccountAgg> rows = m.dailyRows(yesterday);
        assertEquals(List.of(new AccountAgg(10, new WindowAgg(2, 400, 100L, 300L)),
                new AccountAgg(11, WindowAgg.of(50))), rows);

        store.ingest(new Transaction(5, 11, noon.plusSeconds(5), 70));
        assertTrue(m.checkDay(yesterday) > 0, "DAILY_AGG desatualizada deve divergir");
        m.closeDay(yesterday);
        assertEquals(0, m.checkDay(yesterday));
    }

    @Test
    void dropsOnlyPartitionsOlderThanRetention() {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        store.ingest(new Transaction(1, 1, now.minus(100, ChronoUnit.DAYS), 100));
        store.ingest(new Transaction(2, 1, now.minus(10, ChronoUnit.DAYS), 100));
        List<String> dropped = m.dropOldPartitions(today);
        assertFalse(dropped.isEmpty());
        assertEquals(1, m.countTx(), "só a transação de 10 dias atrás fica");
        assertTrue(m.dropOldPartitions(today).isEmpty(), "segunda execução não remove nada");
    }

    @Test
    void bulkInsertsAndStreamsSlices() {
        Instant now = now();
        long m0 = WindowCatalog.epochMinute(now);
        long today = WindowCatalog.epochDay(now, env.zone());
        List<Transaction> txs = new ArrayList<>();
        LongStream.rangeClosed(1, 12_000).forEach(i -> txs.add(new Transaction(i, 1 + i % 3, now.minusSeconds(i % 50), 100)));
        assertEquals(12_000, m.bulkInsertTx(txs.iterator()));
        assertEquals(12_000, m.countTx());
        m.bulkInsertDaily(today - 5, List.of(new AccountAgg(1, new WindowAgg(4, 1000, 100L, 400L))));

        List<KeyedSlice> slices = new ArrayList<>();
        m.streamSlices(now, slices::add);
        long minuteCnt = slices.stream().filter(s -> s.slice().unit() == Unit.MINUTE).mapToLong(s -> s.slice().agg().cnt()).sum();
        long hourCnt = slices.stream().filter(s -> s.slice().unit() == Unit.HOUR).mapToLong(s -> s.slice().agg().cnt()).sum();
        assertEquals(12_000, minuteCnt);
        assertEquals(12_000, hourCnt);
        assertTrue(slices.stream().allMatch(s -> s.slice().unit() != Unit.MINUTE || s.slice().index() <= m0));
        assertTrue(slices.contains(new KeyedSlice(1, new sentinela.domain.Slice(Unit.DAY, today - 5,
                new WindowAgg(4, 1000, 100L, 400L)))));
    }

    @Test
    void readUsesCoveringLocalIndexWithoutTableAccess() {
        store.ingest(new Transaction(1, 1, now(), 100));
        m.gatherStats();
        store.read(1, now());
        String plan = String.join("\n", m.readPlan());
        assertTrue(plan.contains("TX_ACC_TS_AMT_IX"), plan);
        assertTrue(plan.contains("PARTITION RANGE ITERATOR"), plan);
        assertTrue(plan.contains("DAILY_AGG_PK"), plan);
        assertFalse(plan.contains("TABLE ACCESS"), plan);
    }

    @Test
    void isEmptyIgnoresNothingElse() throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_agg VALUES (1, DATE '2026-01-01', 1, 1, 1, 1)")) {
            ps.executeUpdate();
        }
        assertFalse(m.isEmpty(), "DAILY_AGG com linha também conta");
    }
}
