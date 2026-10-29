package sentinela.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.lettuce.core.api.StatefulRedisConnection;
import java.io.PrintStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import oracle.ucp.jdbc.PoolDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Env;

/** Destrutivo. Cenário C com 1.000 contas; leva alguns minutos. */
@Tag("integration")
class EquivalenceIT {
    @Test
    void bothAdaptersAndReferenceAgree() {
        Env env = Env.fromSystem();
        assumeTrue(env.oracleUrl() != null && env.valkeyUrl() != null, "ORACLE_URL ou VALKEY_URL ausente");
        sentinela.testsupport.DestructiveGuard.assumeAllowed(env);
        PoolDataSource ds = OracleDataSources.pool(env, "it-equivalencia", 2, 1000, OracleDataSources.NO_TIMEOUT);
        try (ValkeyConnections valkey = ValkeyConnections.open(env);
                StatefulRedisConnection<String, String> bulk = valkey.openBulk()) {
            LuaScripts scripts = new LuaScripts();
            EquivalenceCheck check = new EquivalenceCheck(
                    new OracleFeatureStore(ds, env.zone()), new OracleMaintenance(ds, env.zone()),
                    new ValkeyFeatureStore(valkey.hot(), scripts, env.zone()), new ValkeyMaintenance(bulk, scripts),
                    env.zone(), new PrintStream(System.err));
            EquivalenceCheck.Report report = check.run(1000, 42, Instant.now().truncatedTo(ChronoUnit.MICROS), true);
            String failures = report.checks().stream().filter(r -> !r.passed())
                    .map(r -> r.name() + "\n  " + String.join("\n  ", r.details())).collect(Collectors.joining("\n"));
            assertTrue(report.allPassed(), failures);
        } finally {
            OracleDataSources.close(ds);
        }
    }
}
