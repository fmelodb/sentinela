package sentinela.app;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import oracle.ucp.jdbc.PoolDataSource;
import sentinela.adapter.oracle.OracleDataSources;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.LuaScripts;
import sentinela.adapter.valkey.ValkeyConnections;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.domain.WindowCatalog;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;

/** Fecha um dia: DAILY_AGG a partir de TX, fatias d: no Valkey e remoção de partições antigas. Idempotente. */
public final class DailyJobApp {
    static final Map<String, String> DEFAULTS = Args.defaults("mode", "oracle", "day", null);

    private DailyJobApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        Env env = Env.fromSystem();
        long today = WindowCatalog.epochDay(Instant.now(), env.zone());
        long day = parseDay(a.optional("day"), today);

        PoolDataSource ds = OracleDataSources.pool(env, "job-diario", 1, 1000, OracleDataSources.NO_TIMEOUT);
        ValkeyConnections valkey = mode == Mode.ORACLE_VALKEY ? ValkeyConnections.open(env) : null;
        StatefulRedisConnection<String, String> bulk = valkey == null ? null : valkey.openBulk();
        try {
            OracleMaintenance om = new OracleMaintenance(ds, env.zone());
            int rows = om.closeDay(day);
            System.err.printf("job-diario: DAILY_AGG de %s com %d contas%n", LocalDate.ofEpochDay(day), rows);
            if (bulk != null) {
                int written = new ValkeyMaintenance(bulk, new LuaScripts()).putDays(day, om.dailyRows(day), today);
                System.err.printf("job-diario: %d fatias d:%d gravadas no Valkey%n", written, day);
            }
            List<String> dropped = om.dropOldPartitions(today);
            System.err.println("job-diario: partições removidas: " + (dropped.isEmpty() ? "nenhuma" : dropped));
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

    static long parseDay(Optional<String> arg, long todayEpochDay) {
        if (arg.isEmpty()) {
            return todayEpochDay - 1;
        }
        long day;
        try {
            day = LocalDate.parse(arg.get()).toEpochDay();
        } catch (DateTimeParseException e) {
            throw new UsageException("--day precisa ser YYYY-MM-DD: " + arg.get());
        }
        if (day >= todayEpochDay) {
            throw new UsageException("--day precisa ser um dia fechado, anterior a hoje: " + arg.get());
        }
        return day;
    }
}
