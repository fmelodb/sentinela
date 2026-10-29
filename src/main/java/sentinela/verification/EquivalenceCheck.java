package sentinela.verification;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import sentinela.adapter.oracle.OracleMaintenance;
import sentinela.adapter.valkey.ValkeyMaintenance;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;
import sentinela.infra.UsageException;

/** Cenário C e critérios funcionais sem carga: mesma sequência nos dois adapters, leituras no mesmo instante lógico. */
public final class EquivalenceCheck {
    public record CheckResult(String name, boolean passed, List<String> details) {}

    public record Report(List<CheckResult> checks) {
        public boolean allPassed() {
            return checks.stream().allMatch(CheckResult::passed);
        }
    }

    private static final int MAX_DETAILS = 20;
    private static final int LAST_MINUTE_WINDOW = 5; // 1h
    private static final int DAY_SLICES = 92;

    private final FeatureStorePort oracle;
    private final OracleMaintenance om;
    private final FeatureStorePort valkey;
    private final ValkeyMaintenance vm;
    private final ZoneId zone;
    private final PrintStream progress;

    public EquivalenceCheck(FeatureStorePort oracle, OracleMaintenance om, FeatureStorePort valkey,
            ValkeyMaintenance vm, ZoneId zone, PrintStream progress) {
        this.oracle = oracle;
        this.om = om;
        this.valkey = valkey;
        this.vm = vm;
        this.zone = zone;
        this.progress = progress;
    }

    public Report run(int accounts, long seed, Instant now, boolean reset) {
        if (!reset && !(om.isEmpty() && vm.isEmpty())) {
            throw new UsageException("os stores não estão vazios; rode com --reset (apaga TX, DAILY_AGG e o Valkey)");
        }
        om.reset();
        vm.reset();

        List<Transaction> txs = EquivalenceData.generate(accounts, seed, now, zone);
        progress.printf("equivalencia: aplicando %d transações nos dois adapters%n", txs.size());
        ingestAll(txs, true);

        long today = WindowCatalog.epochDay(now, zone);
        List<String> jobDetails = new ArrayList<>();
        for (long d = today - DAY_SLICES; d < today; d++) {
            om.closeDay(d);
            vm.putDays(d, om.dailyRows(d), today);
            long diff = om.checkDay(d);
            if (diff != 0) {
                add(jobDetails, "dia " + LocalDate.ofEpochDay(d) + ": " + diff + " linha(s) divergentes");
            }
        }
        progress.println("equivalencia: dias fechados de " + LocalDate.ofEpochDay(today - DAY_SLICES)
                + " a " + LocalDate.ofEpochDay(today - 1));

        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));

        List<FeatureVector> oracleVectors = new ArrayList<>(accounts);
        List<String> shapeDetails = new ArrayList<>();
        List<String> ovDetails = new ArrayList<>();
        List<String> orDetails = new ArrayList<>();
        List<String> silentDetails = new ArrayList<>();
        List<String> todayDetails = new ArrayList<>();
        List<String> names = FeatureVector.names();
        if (names.size() != 95 || !names.get(0).equals("cnt_1m") || !names.get(94).equals("avg_92d")) {
            add(shapeDetails, "nomes fora da ordem da spec: " + names);
        }
        for (long acc = 1; acc <= accounts; acc++) {
            FeatureVector vo = oracle.read(acc, now);
            FeatureVector vv = valkey.read(acc, now);
            FeatureVector vr = ReferenceModel.compute(byAccount.getOrDefault(acc, List.of()), now, zone);
            oracleVectors.add(vo);
            if (vo.values().size() != 95 || vv.values().size() != 95) {
                add(shapeDetails, "conta " + acc + ": vetor sem 95 posições");
            }
            if (!vo.equals(vv)) {
                diff(acc, "oracle", vo, "valkey", vv).forEach(s -> add(ovDetails, s));
            }
            if (!vo.equals(vr)) {
                diff(acc, "oracle", vo, "referencia", vr).forEach(s -> add(orDetails, s));
            }
            int cls = EquivalenceData.accountClass(acc);
            long accountTxs = byAccount.getOrDefault(acc, List.of()).size();
            for (int w = 0; w < WindowCatalog.SIZE; w++) {
                boolean minuteWindow = w <= LAST_MINUTE_WINDOW;
                boolean dayWindow = w >= WindowCatalog.FIRST_DAY_WINDOW;
                String name = "cnt_" + WindowCatalog.SUFFIXES.get(w);
                if (cls == EquivalenceData.SILENT_LAST_HOUR && minuteWindow && (vo.agg(w).cnt() != 0 || vv.agg(w).cnt() != 0)) {
                    add(silentDetails, "conta " + acc + " " + name + ": oracle=" + vo.agg(w).cnt() + " valkey=" + vv.agg(w).cnt());
                }
                if (cls == EquivalenceData.TODAY_ONLY && dayWindow
                        && (vo.agg(w).cnt() != accountTxs || vv.agg(w).cnt() != accountTxs)) {
                    add(todayDetails, "conta " + acc + " " + name + ": esperado=" + accountTxs + " oracle="
                            + vo.agg(w).cnt() + " valkey=" + vv.agg(w).cnt());
                }
            }
        }

        long before = om.countTx();
        progress.println("equivalencia: reaplicando a sequência no Oracle");
        ingestAll(txs, false);
        long after = om.countTx();
        List<String> idemDetails = new ArrayList<>();
        if (before != after) {
            add(idemDetails, "COUNT(*) de TX mudou de " + before + " para " + after);
        }
        for (long acc = 1; acc <= accounts; acc++) {
            FeatureVector again = oracle.read(acc, now);
            if (!again.equals(oracleVectors.get((int) acc - 1))) {
                diff(acc, "antes", oracleVectors.get((int) acc - 1), "depois", again).forEach(s -> add(idemDetails, s));
            }
        }

        return new Report(List.of(
                result("read devolve 95 valores na ordem da seção 3", shapeDetails),
                result("Oracle = Valkey nas 95 posições (" + accounts + " contas)", ovDetails),
                result("Oracle = modelo de referência", orDetails),
                result("sem transação na última hora: cnt = 0 nos minutos e em 1h", silentDetails),
                result("janelas de dias incluem o dia corrente até now", todayDetails),
                result("Job diário = recálculo direto sobre TX", jobDetails),
                result("reaplicar a sequência no Oracle não altera os contadores", idemDetails)));
    }

    public static List<String> diff(long account, String labelA, FeatureVector a, String labelB, FeatureVector b) {
        List<String> out = new ArrayList<>();
        List<BigDecimal> va = a.values();
        List<BigDecimal> vb = b.values();
        List<String> names = FeatureVector.names();
        for (int i = 0; i < names.size(); i++) {
            if (!Objects.equals(va.get(i), vb.get(i))) {
                out.add("conta " + account + " " + names.get(i) + ": " + labelA + "=" + plain(va.get(i)) + " " + labelB
                        + "=" + plain(vb.get(i)));
            }
        }
        return out;
    }

    private void ingestAll(List<Transaction> txs, boolean bothStores) {
        int n = 0;
        for (Transaction t : txs) {
            oracle.ingest(t);
            if (bothStores) {
                valkey.ingest(t);
            }
            if (++n % 20_000 == 0) {
                progress.printf("equivalencia: %d/%d%n", n, txs.size());
            }
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }

    private static void add(List<String> details, String s) {
        if (details.size() < MAX_DETAILS) {
            details.add(s);
        } else if (details.size() == MAX_DETAILS) {
            details.add("…");
        }
    }

    private static CheckResult result(String name, List<String> details) {
        return new CheckResult(name, details.isEmpty(), List.copyOf(details));
    }
}
