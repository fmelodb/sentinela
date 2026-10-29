package sentinela.adapter.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Slice;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Operações fora do port: Job diário, retenção, reset, carga em massa e reconstrução do Valkey. */
public final class OracleMaintenance {
    static final int TX_RETENTION_DAYS = 93;
    static final int DAY_SLICES = 92;
    private static final int BATCH = 5_000;
    private static final int COMMIT_EVERY = 50_000;
    private static final Pattern HIGH_VALUE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");

    private static final String RECALC = "SELECT account_id, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
            + " FROM tx WHERE ts >= ? AND ts < ? GROUP BY account_id";
    private static final String STORED = "SELECT account_id, cnt, sum_amount, min_amount, max_amount"
            + " FROM daily_agg WHERE dia = ?";

    private final DataSource ds;
    private final ZoneId zone;

    public OracleMaintenance(DataSource ds, ZoneId zone) {
        this.ds = ds;
        this.zone = zone;
    }

    public void reset() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE TABLE tx DROP ALL STORAGE");
            st.execute("TRUNCATE TABLE daily_agg");
        } catch (SQLException e) {
            throw new StoreException("reset do Oracle", e);
        }
    }

    public boolean isEmpty() {
        return queryLong("SELECT (SELECT COUNT(*) FROM (SELECT 1 FROM tx FETCH FIRST 1 ROWS ONLY))"
                + " + (SELECT COUNT(*) FROM (SELECT 1 FROM daily_agg FETCH FIRST 1 ROWS ONLY)) FROM dual") == 0;
    }

    public long countTx() {
        return queryLong("SELECT COUNT(*) FROM tx");
    }

    public int closeDay(long epochDay) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM daily_agg WHERE dia = ?");
                    PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount)"
                                    + " SELECT account_id, ?, COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
                                    + " FROM tx WHERE ts >= ? AND ts < ? GROUP BY account_id")) {
                del.setObject(1, OracleTime.day(epochDay));
                del.executeUpdate();
                ins.setObject(1, OracleTime.day(epochDay));
                ins.setObject(2, OracleTime.dayStartUtc(epochDay, zone));
                ins.setObject(3, OracleTime.dayStartUtc(epochDay + 1, zone));
                int n = ins.executeUpdate();
                c.commit();
                return n;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("fechamento do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
    }

    public long checkDay(long epochDay) {
        String sql = "SELECT COUNT(*) FROM ((" + STORED + " MINUS " + RECALC + ") UNION ALL (" + RECALC + " MINUS "
                + STORED + "))";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            LocalDate dia = OracleTime.day(epochDay);
            LocalDateTime ini = OracleTime.dayStartUtc(epochDay, zone);
            LocalDateTime fim = OracleTime.dayStartUtc(epochDay + 1, zone);
            ps.setObject(1, dia);
            ps.setObject(2, ini);
            ps.setObject(3, fim);
            ps.setObject(4, ini);
            ps.setObject(5, fim);
            ps.setObject(6, dia);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new StoreException("verificação do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
    }

    public List<AccountAgg> dailyRows(long epochDay) {
        List<AccountAgg> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(STORED + " ORDER BY account_id")) {
            ps.setObject(1, OracleTime.day(epochDay));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AccountAgg(rs.getLong(1), agg(rs, 2)));
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de DAILY_AGG do dia " + LocalDate.ofEpochDay(epochDay), e);
        }
        return out;
    }

    public List<String> dropOldPartitions(long todayEpochDay) {
        LocalDateTime cutoff = OracleTime.dayStartUtc(todayEpochDay - TX_RETENTION_DAYS, zone);
        List<String> dropped = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            List<String> candidates = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                    "SELECT partition_name, high_value FROM user_tab_partitions"
                            + " WHERE table_name = 'TX' AND partition_position > 1 ORDER BY partition_position")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Matcher mt = HIGH_VALUE.matcher(rs.getString(2));
                    if (mt.find() && !LocalDateTime.parse(mt.group(1).replace(' ', 'T')).isAfter(cutoff)) {
                        candidates.add(name);
                    }
                }
            }
            try (Statement st = c.createStatement()) {
                for (String name : candidates) {
                    st.execute("ALTER TABLE tx DROP PARTITION \"" + name + "\" UPDATE GLOBAL INDEXES");
                    dropped.add(name);
                }
            }
        } catch (SQLException e) {
            throw new StoreException("remoção de partições antigas", e);
        }
        return dropped;
    }

    public void gatherStats() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, 'TX'); DBMS_STATS.GATHER_TABLE_STATS(USER, 'DAILY_AGG'); END;");
        } catch (SQLException e) {
            throw new StoreException("coleta de estatísticas", e);
        }
    }

    public long bulkInsertTx(Iterator<Transaction> txs) {
        String sql = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (?, ?, ?, ?)";
        return bulk(sql, txs, (ps, tx) -> {
            ps.setLong(1, tx.txId());
            ps.setLong(2, tx.accountId());
            ps.setObject(3, OracleTime.utc(tx.ts()));
            ps.setBigDecimal(4, OracleTime.amount(tx.amountCents()));
        });
    }

    public long bulkInsertDaily(long epochDay, List<AccountAgg> rows) {
        String sql = "INSERT INTO daily_agg (account_id, dia, cnt, sum_amount, min_amount, max_amount) VALUES (?, ?, ?, ?, ?, ?)";
        LocalDate dia = OracleTime.day(epochDay);
        return bulk(sql, rows.iterator(), (ps, r) -> {
            ps.setLong(1, r.accountId());
            ps.setObject(2, dia);
            ps.setLong(3, r.agg().cnt());
            ps.setBigDecimal(4, OracleTime.amount(r.agg().sumCents()));
            ps.setBigDecimal(5, OracleTime.amount(r.agg().minCents()));
            ps.setBigDecimal(6, OracleTime.amount(r.agg().maxCents()));
        });
    }

    public void streamSlices(Instant now, Consumer<KeyedSlice> sink) {
        long minute = WindowCatalog.epochMinute(now);
        long hour = WindowCatalog.epochHour(now);
        long today = WindowCatalog.epochDay(now, zone);
        try (Connection c = ds.getConnection()) {
            streamTxSlices(c, "MI", Unit.MINUTE, OracleTime.sliceStart(Unit.MINUTE, minute - 59), sink);
            streamTxSlices(c, "HH", Unit.HOUR, OracleTime.sliceStart(Unit.HOUR, hour - 71), sink);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT account_id, dia, cnt, sum_amount, min_amount, max_amount FROM daily_agg WHERE dia >= ? AND dia < ?")) {
                ps.setObject(1, OracleTime.day(today - DAY_SLICES));
                ps.setObject(2, OracleTime.day(today));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long day = rs.getObject(2, LocalDate.class).toEpochDay();
                        sink.accept(new KeyedSlice(rs.getLong(1), new Slice(Unit.DAY, day, agg(rs, 3))));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura de fatias para o Valkey", e);
        }
    }

    public List<String> readPlan() {
        List<String> lines = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            String sqlId;
            int child;
            try (PreparedStatement ps = c.prepareStatement("SELECT sql_id, child_number FROM v$sql"
                    + " WHERE sql_text LIKE ? AND parsing_schema_name = USER ORDER BY last_active_time DESC"
                    + " FETCH FIRST 1 ROWS ONLY")) {
                ps.setString(1, OracleFeatureStore.READ_SQL_PREFIX + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new StoreException("SQL de leitura não encontrado em V$SQL; execute uma leitura antes", null);
                    }
                    sqlId = rs.getString(1);
                    child = rs.getInt(2);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR(?, ?, 'BASIC +PARTITION'))")) {
                ps.setString(1, sqlId);
                ps.setInt(2, child);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new StoreException("leitura do plano de execução", e);
        }
        return lines;
    }

    @FunctionalInterface
    private interface Binder<T> {
        void bind(PreparedStatement ps, T item) throws SQLException;
    }

    private <T> long bulk(String sql, Iterator<T> items, Binder<T> binder) {
        long n = 0;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                while (items.hasNext()) {
                    binder.bind(ps, items.next());
                    ps.addBatch();
                    n++;
                    if (n % BATCH == 0) {
                        ps.executeBatch();
                    }
                    if (n % COMMIT_EVERY == 0) {
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException("carga em massa (" + n + " linhas enviadas)", e);
        }
        return n;
    }

    private void streamTxSlices(Connection c, String fmt, Unit unit, LocalDateTime from, Consumer<KeyedSlice> sink)
            throws SQLException {
        String sql = "SELECT account_id, TRUNC(ts, '" + fmt + "'), COUNT(*), SUM(amount), MIN(amount), MAX(amount)"
                + " FROM tx WHERE ts >= ? GROUP BY account_id, TRUNC(ts, '" + fmt + "')";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, from);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant start = OracleTime.instant(rs.getObject(2, LocalDateTime.class));
                    long index = unit == Unit.MINUTE ? WindowCatalog.epochMinute(start) : WindowCatalog.epochHour(start);
                    sink.accept(new KeyedSlice(rs.getLong(1), new Slice(unit, index, agg(rs, 3))));
                }
            }
        }
    }

    private static WindowAgg agg(ResultSet rs, int firstColumn) throws SQLException {
        return new WindowAgg(rs.getLong(firstColumn), OracleTime.cents(rs.getBigDecimal(firstColumn + 1)),
                OracleTime.cents(rs.getBigDecimal(firstColumn + 2)), OracleTime.cents(rs.getBigDecimal(firstColumn + 3)));
    }

    private long queryLong(String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new StoreException("consulta: " + sql, e);
        }
    }
}
