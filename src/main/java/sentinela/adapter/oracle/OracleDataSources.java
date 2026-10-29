package sentinela.adapter.oracle;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import oracle.ucp.UniversalConnectionPoolException;
import oracle.ucp.admin.UniversalConnectionPoolManagerImpl;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;
import sentinela.infra.Env;
import sentinela.infra.StoreUnavailableException;

/** Pool UCP de tamanho fixo, cheio antes de o relógio do benchmark começar. */
public final class OracleDataSources {
    public static final int HOT_READ_TIMEOUT_MS = 5_000;
    public static final int NO_TIMEOUT = 0;

    private OracleDataSources() {}

    public static PoolDataSource pool(Env env, String name, int size, int rowPrefetch, int readTimeoutMs) {
        env.requireOracle();
        try {
            PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();
            pds.setConnectionPoolName(name + "-" + System.nanoTime());
            pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
            pds.setURL(env.oracleUrl());
            pds.setUser(env.oracleUser());
            pds.setPassword(env.oraclePassword());
            pds.setInitialPoolSize(size);
            pds.setMinPoolSize(size);
            pds.setMaxPoolSize(size);
            pds.setValidateConnectionOnBorrow(false);
            Properties p = new Properties();
            p.setProperty("oracle.jdbc.implicitStatementCacheSize", "20");
            p.setProperty("defaultRowPrefetch", Integer.toString(rowPrefetch));
            if (readTimeoutMs > 0) {
                p.setProperty("oracle.jdbc.ReadTimeout", Integer.toString(readTimeoutMs));
            }
            pds.setConnectionProperties(p);
            try (Connection c = pds.getConnection()) {
                if (!c.isValid(5)) {
                    throw new SQLException("conexão inválida");
                }
            }
            return pds;
        } catch (SQLException e) {
            throw new StoreUnavailableException("Oracle em " + env.oracleUrl(), e);
        }
    }

    public static void close(PoolDataSource pds) {
        try {
            UniversalConnectionPoolManagerImpl.getUniversalConnectionPoolManager()
                    .destroyConnectionPool(pds.getConnectionPoolName());
        } catch (UniversalConnectionPoolException e) {
            System.err.println("aviso: falha ao fechar o pool " + pds.getConnectionPoolName() + ": " + e.getMessage());
        }
    }
}
