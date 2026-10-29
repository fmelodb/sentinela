package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleFeatureStore;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.oracle.PlanCheck;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.BackfillGenerator;

/** Destrutivo: esvazia os stores e popula 92 dias de histórico. No modo oracle+valkey, reconstrói o Valkey. */
public final class BackfillApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "accounts", "100000",
            "days", "92",
            "seed", "42",
            "distribution", "skewed",
            "tx-per-day", "5");

    private BackfillApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int accounts = a.positiveInt("accounts");
        int days = a.positiveInt("days");
        if (days < BackfillGenerator.RECENT_DAYS || days > 92) {
            throw new UsageException("--days precisa estar entre 3 e 92: " + days);
        }
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        int txPerDay = a.positiveInt("tx-per-day");
        Env env = Env.fromSystem();

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        BackfillGenerator gen = new BackfillGenerator(accounts, distribution, txPerDay, seed, env.zone(), now);
        long today = gen.today();
        PoolDataSource ds = OracleDataSources.pool(env, "backfill", 2, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = mode == Mode.ORACLE_VALKEY ? ValkeyConnections.open(env) : null;
        StatefulRedisConnection<String, String> bulk = valkey == null ? null : valkey.openBulk();
        try {
            OracleMaintenance om = new OracleMaintenance(ds, env.zone());
            ValkeyMaintenance vm = bulk == null ? null : new ValkeyMaintenance(bulk, new LuaScripts());
            long t0 = System.nanoTime();

            om.reset();
            if (vm != null) {
                vm.reset();
            }
            step(t0, "stores esvaziados");

            long n = om.bulkInsertTx(gen.recentTransactions());
            step(t0, n + " transações em TX desde " + LocalDate.ofEpochDay(today - BackfillGenerator.RECENT_DAYS));

            for (long d = today - BackfillGenerator.RECENT_DAYS; d < today; d++) {
                int rows = om.closeDay(d);
                step(t0, "DAILY_AGG de " + LocalDate.ofEpochDay(d) + " a partir de TX: " + rows + " contas");
            }

            long synthetic = 0;
            for (long d = today - days; d < today - BackfillGenerator.RECENT_DAYS; d++) {
                synthetic += om.bulkInsertDaily(d, gen.syntheticDay(d));
                if ((d - (today - days)) % 10 == 9) {
                    step(t0, "DAILY_AGG sintético até " + LocalDate.ofEpochDay(d) + ": " + synthetic + " linhas");
                }
            }
            step(t0, "DAILY_AGG sintético: " + synthetic + " linhas");

            om.gatherStats();
            step(t0, "estatísticas coletadas");

            // Spec técnica 5.3: confere o plano real da leitura sobre o volume da rodada; plano errado invalida a rodada.
            new OracleFeatureStore(ds, env.zone()).read(1, now);
            List<String> plan = om.readPlan();
            List<String> problems = PlanCheck.problems(plan);
            if (!problems.isEmpty()) {
                plan.forEach(System.err::println);
                problems.forEach(p -> System.err.println("backfill: plano de leitura inválido: " + p));
                return ExitCodes.INVALID_RUN;
            }
            step(t0, "plano de leitura ok (índice coberto, poda de partições, sem acesso à tabela)");

            if (vm != null) {
                try (ValkeyMaintenance.SliceWriter w = vm.sliceWriter()) {
                    om.streamRecent(now, w::writeTx);
                    om.streamDays(now, w::writeDay);
                    w.close();
                    step(t0, "Valkey reconstruído: " + w.written() + " comandos (transações de 72h e dias fechados)");
                }
            }
            return ExitCodes.OK;
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

    private static void step(long t0, String msg) {
        System.err.printf("backfill [%5.1fs] %s%n", (System.nanoTime() - t0) / 1e9, msg);
    }
}
