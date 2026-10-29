package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyFeatureStore;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.verification.EquivalenceCheck;

/** Cenário C da spec funcional. Destrutivo: exige stores vazios ou --reset. */
public final class EquivalenceApp {
    static final Map<String, String> DEFAULTS = Args.defaults("accounts", "1000", "seed", "42", "now", null);

    private EquivalenceApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of("reset"));
        int accounts = a.positiveInt("accounts");
        if (accounts < 1000) {
            System.err.println("aviso: o critério de aceitação exige pelo menos 1.000 contas; rodando com " + accounts);
        }
        long seed = a.longValue("seed");
        Instant now;
        try {
            now = a.optional("now").map(Instant::parse).orElseGet(Instant::now).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new UsageException("--now precisa ser ISO-8601, como 2026-10-29T15:07:30Z: " + a.string("now"));
        }
        Env env = Env.fromSystem();

        PoolDataSource ds = OracleDataSources.pool(env, "equivalencia", 2, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = null;
        StatefulRedisConnection<String, String> bulk = null;
        try {
            valkey = ValkeyConnections.open(env);
            bulk = valkey.openBulk();
            LuaScripts scripts = new LuaScripts();
            EquivalenceCheck check = new EquivalenceCheck(
                    new OracleFeatureStore(ds, env.zone()), new OracleMaintenance(ds, env.zone()),
                    new ValkeyFeatureStore(valkey.hot(), scripts, env.zone()), new ValkeyMaintenance(bulk, scripts),
                    env.zone(), System.err);
            EquivalenceCheck.Report report = check.run(accounts, seed, now, a.flag("reset"));
            System.out.println("equivalencia em " + now + " (" + env.zone() + "), " + accounts + " contas, seed " + seed);
            for (EquivalenceCheck.CheckResult r : report.checks()) {
                System.out.println((r.passed() ? "PASS  " : "FAIL  ") + r.name());
                r.details().forEach(d -> System.out.println("      " + d));
            }
            return report.allPassed() ? ExitCodes.OK : ExitCodes.VERIFICATION_FAILED;
        } finally {
            if (bulk != null) {
                bulk.close();
            }
            if (valkey != null) {
                valkey.close();
            }
            OracleDataSources.close(ds);
        }
    }
}
