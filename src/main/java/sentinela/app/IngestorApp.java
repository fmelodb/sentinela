package sentinela.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sentinela.domain.Transaction;
import sentinela.infra.Args;
import sentinela.infra.CsvLog;
import sentinela.infra.Env;
import sentinela.infra.ErrorReporter;
import sentinela.infra.ExitCodes;
import sentinela.infra.RunMeta;
import sentinela.workload.Dataset;
import sentinela.workload.LoadRunner;

/** Aplica o dataset nos stores à taxa alvo, com ts = agora. Substitui o consumer Kafka. */
public final class IngestorApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "mode", "oracle",
            "tps", "600",
            "duration", "300",
            "warmup", "30",
            "dataset", "./data/tx.ndjson",
            "log", null,
            "threads", "8",
            "run", null);

    private IngestorApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        Mode mode = Mode.parse(a.string("mode"));
        int tps = a.positiveInt("tps");
        int duration = a.positiveInt("duration");
        int warmup = a.integer("warmup");
        int threads = a.positiveInt("threads");
        Path dataset = a.path("dataset");
        Env env = Env.fromSystem();

        List<Dataset.Row> rows = Dataset.readAll(dataset);
        if ((long) tps * duration > rows.size()) {
            System.err.printf("aviso: dataset com %d transações não cobre tps × duration = %d%n", rows.size(),
                    (long) tps * duration);
        }
        String run = a.optional("run").orElse(LoadPaths.defaultRun(Instant.now()));
        Path log = a.optional("log").map(Path::of).orElse(LoadPaths.defaultLog("ingestor", mode, run));
        Clock clock = Clock.systemUTC();
        Iterator<Dataset.Row> it = rows.iterator();
        Iterator<Transaction> source = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Transaction next() {
                Dataset.Row r = it.next();
                return new Transaction(r.txId(), r.accountId(), clock.instant().truncatedTo(ChronoUnit.MICROS), r.amountCents());
            }
        };

        try (Modes.Wired<List<NamedSink>> sinks = Modes.ingestSinks(mode, env, threads);
                CsvLog csv = new CsvLog(log, "ingestor", mode.cli());
                ErrorReporter errors = new ErrorReporter(System.err)) {
            List<LoadRunner.Step<Transaction>> steps = sinks.value().stream()
                    .map(s -> new LoadRunner.Step<Transaction>(s.operation(), s.port()::ingest, Transaction::accountId))
                    .toList();
            Map<String, String> meta = LoadPaths.meta("ingestor", mode, run, tps, duration, warmup, threads,
                    System.currentTimeMillis());
            meta.put("dataset", dataset.toString());
            RunMeta.write(log, meta);
            LoadRunner.Result r = new LoadRunner<>(source, steps, tps, Duration.ofSeconds(duration), threads, csv, errors).run();
            RunMeta.put(log, "end_epoch_ms", Long.toString(System.currentTimeMillis()));
            System.err.printf("ingestor: %d transações despachadas, %d erro(s) -> %s%n", r.dispatched(), errors.total(), log);
            if (r.sourceExhausted()) {
                System.err.println("rodada inválida: o dataset acabou antes de --duration; gere com --count maior");
                return ExitCodes.INVALID_RUN;
            }
            return ExitCodes.OK;
        }
    }
}
