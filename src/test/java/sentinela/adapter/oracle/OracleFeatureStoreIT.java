package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

    @Test
    void readsMinuteAndHourWindowsAndIgnoresDuplicates() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES).plusSeconds(30);
        store.ingest(new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000));
        store.ingest(new Transaction(9_900_000_002L, ACC, now.minus(2, ChronoUnit.HOURS), 2000));
        store.ingest(new Transaction(9_900_000_003L, ACC, now.minus(30, ChronoUnit.HOURS), 3000));
        long before = store.duplicates();
        store.ingest(new Transaction(9_900_000_001L, ACC, now.minusSeconds(10), 1000));
        assertEquals(before + 1, store.duplicates());

        FeatureVector v = store.read(ACC, now);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(5), "1h");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h = hora corrente e a anterior");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(12), "24h");
        assertEquals(new WindowAgg(3, 6000, 1000L, 3000L), v.agg(13), "48h");
        assertEquals(WindowAgg.EMPTY, v.agg(15), "7d sem DAILY_AGG");
    }

    @Test
    void readsDayWindowsFromDailyAggExcludingToday() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long today = WindowCatalog.epochDay(now, env.zone());
        insertDaily(today - 1, 2, "5.00", "1.00", "4.00");
        insertDaily(today - 8, 1, "0.50", "0.50", "0.50");
        insertDaily(today, 9, "90.00", "10.00", "10.00");
        FeatureVector v = store.read(ACC, now);
        assertEquals(new WindowAgg(2, 500, 100L, 400L), v.agg(15), "7d");
        assertEquals(new WindowAgg(3, 550, 50L, 400L), v.agg(16), "31d");
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
