package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import oracle.ucp.jdbc.PoolDataSource;
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
class OracleFeatureStoreIT {
    private static final long ACC = 990_000_001L;
    private static Env env;
    private static PoolDataSource ds;
    private static OracleFeatureStore store;

    @BeforeAll
    static void setUp() {
        env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null, "ORACLE_URL ausente");
        ds = OracleDataSources.pool(env, "it-oracle", 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
        store = new OracleFeatureStore(ds, env.zone());
    }

    @AfterAll
    static void tearDown() {
        if (ds != null) {
            OracleDataSources.close(ds);
        }
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection()) {
            for (String sql : new String[] {"DELETE FROM tx WHERE account_id = ?", "DELETE FROM daily_agg WHERE account_id = ?"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setLong(1, ACC);
                    ps.executeUpdate();
                }
            }
        }
    }

    @Test
    void unknownAccountReadsEmptyVector() {
        assertEquals(FeatureVector.empty(), store.read(ACC, Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void readsRollingWindowsAndIgnoresDuplicates() {
        Instant now = now();
        Transaction t1 = new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000);
        Transaction t2 = new Transaction(9_900_000_002L, ACC, now.minus(2, ChronoUnit.HOURS).plusSeconds(1), 2000);
        Transaction t3 = new Transaction(9_900_000_003L, ACC, now.minus(30, ChronoUnit.HOURS), 3000);
        store.ingest(t1);
        store.ingest(t2);
        store.ingest(t3);
        long before = store.duplicates();
        store.ingest(t1);
        assertEquals(before + 1, store.duplicates());

        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(6), "2h = exatamente as últimas 2 horas");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
        long today = WindowCatalog.epochDay(now, env.zone());
        WindowAgg todays = List.of(t1, t2, t3).stream().filter(t -> WindowCatalog.epochDay(t.ts(), env.zone()) == today)
                .map(t -> WindowAgg.of(t.amountCents())).reduce(WindowAgg.EMPTY, WindowAgg::plus);
        assertEquals(todays, v.agg(15), "7d sem DAILY_AGG = só o dia corrente");
    }

    @Test
    void rollingLowerBoundIsExclusiveAndUpperInclusive() {
        Instant now = now();
        Instant lo5m = now.minus(5, ChronoUnit.MINUTES);
        store.ingest(new Transaction(9_900_000_011L, ACC, lo5m, 100));
        store.ingest(new Transaction(9_900_000_012L, ACC, lo5m.plusNanos(1_000), 200));
        store.ingest(new Transaction(9_900_000_013L, ACC, now, 300));
        store.ingest(new Transaction(9_900_000_014L, ACC, now.plusNanos(1_000), 400));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 200L, 300L), v.agg(1), "5m");
        assertEquals(new WindowAgg(3, 600, 100L, 300L), v.agg(2), "10m");
    }

    @Test
    void dayWindowsCombineDailyAggWithToday() throws Exception {
        Instant now = now();
        long today = WindowCatalog.epochDay(now, env.zone());
        Instant todayStart = WindowCatalog.dayStart(today, env.zone());
        insertDaily(today - 1, 2, "5.00", "1.00", "4.00");
        insertDaily(today - 7, 1, "0.50", "0.50", "0.50");
        insertDaily(today, 9, "90.00", "10.00", "10.00");
        store.ingest(new Transaction(9_900_000_021L, ACC, todayStart, 1000));
        store.ingest(new Transaction(9_900_000_022L, ACC, todayStart.minusNanos(1_000), 7));
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(3, 1500, 100L, 1000L), v.agg(15), "7d: T−1 e hoje; T−7 e a linha de hoje ficam fora");
        assertEquals(new WindowAgg(4, 1550, 50L, 1000L), v.agg(16), "31d");
        assertEquals(0, v.agg(0).cnt(), "DAILY_AGG não alimenta minutos");
    }

    private static void insertDaily(long epochDay, int cnt, String sum, String min, String max) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, ACC);
            ps.setObject(2, LocalDate.ofEpochDay(epochDay));
            ps.setInt(3, cnt);
            ps.setBigDecimal(4, new BigDecimal(sum));
            ps.setBigDecimal(5, new BigDecimal(min));
            ps.setBigDecimal(6, new BigDecimal(max));
            ps.executeUpdate();
        }
    }
}
