package sentinela.adapter.oracle;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Oracle como store: TX bruta e DAILY_AGG; leitura em um único statement. */
public final class OracleFeatureStore implements FeatureStorePort {
    /** Início do SQL de leitura como o Oracle o guarda em V$SQL (o JDBC troca ? por :n). */
    public static final String READ_SQL_PREFIX = "WITH b AS (SELECT :1 AS lo_1m";

    private static final String INSERT = "INSERT INTO tx (tx_id, account_id, ts, amount) VALUES (?, ?, ?, ?)";
    private static final int ORA_UNIQUE_CONSTRAINT = 1;

    private final DataSource ds;
    private final ZoneId zone;
    private final LongAdder duplicates = new LongAdder();

    public OracleFeatureStore(DataSource ds, ZoneId zone) {
        this.ds = ds;
        this.zone = zone;
    }

    @Override
    public void ingest(Transaction tx) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setLong(1, tx.txId());
            ps.setLong(2, tx.accountId());
            ps.setObject(3, OracleTime.utc(tx.ts()));
            ps.setBigDecimal(4, OracleTime.amount(tx.amountCents()));
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException e) {
            if (e.getErrorCode() != ORA_UNIQUE_CONSTRAINT) {
                throw new StoreException("ingest no Oracle, tx_id " + tx.txId(), e);
            }
            duplicates.increment(); // idempotência por tx_id
        } catch (SQLException e) {
            throw new StoreException("ingest no Oracle, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Object[] binds = OracleReadSql.binds(accountId, WindowCatalog.bounds(now, zone));
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(OracleReadSql.SQL)) {
            for (int i = 0; i < binds.length; i++) {
                ps.setObject(i + 1, binds[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
                for (int w = 0; w < WindowCatalog.SIZE; w++) {
                    int base = w * 4 + 1;
                    long cnt = rs.getLong(base);
                    if (cnt == 0) {
                        aggs.add(WindowAgg.EMPTY);
                    } else {
                        aggs.add(new WindowAgg(cnt, cents(rs.getBigDecimal(base + 1)),
                                cents(rs.getBigDecimal(base + 2)), cents(rs.getBigDecimal(base + 3))));
                    }
                }
                return new FeatureVector(aggs);
            }
        } catch (SQLException e) {
            throw new StoreException("read no Oracle, conta " + accountId, e);
        }
    }

    public long duplicates() {
        return duplicates.sum();
    }

    private static long cents(BigDecimal v) {
        return OracleTime.cents(v);
    }
}
