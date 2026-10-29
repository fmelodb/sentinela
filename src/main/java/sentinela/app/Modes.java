package sentinela.app;

import java.util.ArrayList;
import java.util.List;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.domain.FeatureStorePort;
import sentinela.infra.Env;

/** Seleção de adapters por --mode. A ingestão grava sempre no Oracle; no modo oracle+valkey, também no Valkey. */
public final class Modes {
    public record Wired<T>(T value, AutoCloseable closer) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            closer.close();
        }
    }

    private Modes() {}

    public static Wired<List<NamedSink>> ingestSinks(Mode mode, Env env, int threads) {
        PoolDataSource ds = OracleDataSources.pool(env, "ingestor", threads + 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
        OracleFeatureStore oracle = new OracleFeatureStore(ds, env.zone());
        List<NamedSink> sinks = new ArrayList<>(List.of(new NamedSink("ingest_oracle", oracle)));
        ValkeyConnections valkey = null;
        if (mode == Mode.ORACLE_VALKEY) {
            try {
                valkey = ValkeyConnections.open(env);
            } catch (RuntimeException e) {
                OracleDataSources.close(ds);
                throw e;
            }
            sinks.add(new NamedSink("ingest_valkey", new ValkeyFeatureStore(valkey.hot(), new LuaScripts(), env.zone())));
        }
        ValkeyConnections v = valkey;
        return new Wired<>(List.copyOf(sinks), () -> {
            System.err.println("ingestor: duplicatas ignoradas no Oracle (tx_id já existente): " + oracle.duplicates());
            if (v != null) {
                v.close();
            }
            OracleDataSources.close(ds);
        });
    }

    public static Wired<FeatureStorePort> reader(Mode mode, Env env, int threads) {
        if (mode == Mode.ORACLE) {
            PoolDataSource ds = OracleDataSources.pool(env, "leitor", threads + 2, 1, OracleDataSources.HOT_READ_TIMEOUT_MS);
            return new Wired<>(new OracleFeatureStore(ds, env.zone()), () -> OracleDataSources.close(ds));
        }
        ValkeyConnections valkey = ValkeyConnections.open(env);
        return new Wired<>(new ValkeyFeatureStore(valkey.hot(), new LuaScripts(), env.zone()), valkey);
    }
}
