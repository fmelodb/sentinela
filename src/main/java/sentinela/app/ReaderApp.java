package sentinela.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import sentinela.domain.FeatureStorePort;
import sentinela.infra.Args;
import sentinela.infra.CsvLog;
import sentinela.infra.Env;
import sentinela.infra.ErrorReporter;
import sentinela.infra.ExitCodes;
import sentinela.infra.RunMeta;
import sentinela.workload.AccountSampler;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.LoadRunner;
import sentinela.workload.Seeds;

/** Sorteia contas e lê o vetor de 95 features à taxa alvo. Substitui o serviço de scoring. */
public final class ReaderApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "tps", "600",
            "duration", "300",
            "warmup", "30",
            "accounts", "100000",
            "seed", "42",
            "distribution", "skewed",
            "log", null,
            "threads", "8",
            "run", null);

    private ReaderApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int tps = a.positiveInt("tps");
        int duration = a.positiveInt("duration");
        int warmup = a.integer("warmup");
        int threads = a.positiveInt("threads");
        int accounts = a.positiveInt("accounts");
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        Env env = Env.fromSystem();

        String run = a.optional("run").orElse(LoadPaths.defaultRun(Instant.now()));
        Path log = a.optional("log").map(Path::of).orElse(LoadPaths.defaultLog("reader", mode, run));
        Clock clock = Clock.systemUTC();
        AccountSampler sampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "leitor.contas"));
        Iterator<Long> source = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public Long next() {
                return sampler.next();
            }
        };

        try (Modes.Wired<FeatureStorePort> reader = Modes.reader(mode, env, threads);
                CsvLog csv = new CsvLog(log, "reader", mode.cli());
                ErrorReporter errors = new ErrorReporter(System.err)) {
            FeatureStorePort port = reader.value();
            LoadRunner.Step<Long> step = new LoadRunner.Step<>("read", acc -> port.read(acc, clock.instant()), acc -> acc);
            Map<String, String> meta = LoadPaths.meta("reader", mode, run, tps, duration, warmup, threads,
                    System.currentTimeMillis());
            meta.put("accounts", Integer.toString(accounts));
            meta.put("distribution", a.string("distribution"));
            meta.put("seed", Long.toString(seed));
            RunMeta.write(log, meta);
            LoadRunner.Result r = new LoadRunner<>(source, java.util.List.of(step), tps, Duration.ofSeconds(duration),
                    threads, csv, errors).run();
            RunMeta.put(log, "end_epoch_ms", Long.toString(System.currentTimeMillis()));
            System.err.printf("leitor: %d leituras despachadas, %d erro(s) -> %s%n", r.dispatched(), errors.total(), log);
            return ExitCodes.OK;
        }
    }
}
