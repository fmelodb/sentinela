package sentinela.app;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import sentinela.infra.Args;
import sentinela.infra.ExitCodes;
import sentinela.workload.AccountSampler;
import sentinela.workload.AccountSampler.Distribution;
import sentinela.workload.AmountSampler;
import sentinela.workload.Dataset;
import sentinela.workload.Seeds;

/** Produz o dataset de transações, determinístico por --seed. */
public final class GeneratorApp {
    static final Map<String, String> DEFAULTS = Args.defaults(
            "accounts", "100000",
            "seed", "42",
            "distribution", "skewed",
            "dataset", "./data/tx.ndjson",
            "count", "200000");

    private GeneratorApp() {}

    public static int run(String[] argv) throws Exception {
        Args a = Args.parse(argv, DEFAULTS, Set.of());
        int accounts = a.positiveInt("accounts");
        long seed = a.longValue("seed");
        Distribution distribution = Distribution.parse(a.string("distribution"));
        Path dataset = a.path("dataset");
        int count = a.positiveInt("count");

        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "gerador.contas"));
        AmountSampler amounts = new AmountSampler(Seeds.stream(seed, "gerador.valores"));
        Iterator<Dataset.Row> rows = new Iterator<>() {
            private long next = 1;

            @Override
            public boolean hasNext() {
                return next <= count;
            }

            @Override
            public Dataset.Row next() {
                return new Dataset.Row(next++, accountsSampler.next(), amounts.nextCents());
            }
        };
        Dataset.write(dataset, rows);
        System.err.printf("gerador: %d transações, %d contas (%s), seed %d -> %s%n",
                count, accounts, a.string("distribution"), seed, dataset);
        return ExitCodes.OK;
    }
}
