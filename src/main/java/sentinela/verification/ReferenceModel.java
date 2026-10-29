package sentinela.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import sentinela.domain.DayWindow;
import sentinela.domain.FeatureVector;
import sentinela.domain.RollingWindow;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

/**
 * Cálculo direto das 19 janelas sobre a lista de transações, pela seção 3 da funcional, sem fatias,
 * sem WindowCatalog.limits e sem WindowAggregator. Serve de terceiro voto: se Oracle e Valkey errarem
 * do mesmo jeito, a referência discorda.
 */
public final class ReferenceModel {
    private ReferenceModel() {}

    public static FeatureVector compute(List<Transaction> txs, Instant now, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
        for (RollingWindow w : WindowCatalog.ROLLING) {
            Instant from = now.minus(w.span());
            aggs.add(sum(txs, ts -> ts.isAfter(from) && !ts.isAfter(now)));
        }
        for (DayWindow w : WindowCatalog.DAYS) {
            Instant from = today.minusDays(w.n() - 1).atStartOfDay(zone).toInstant();
            aggs.add(sum(txs, ts -> !ts.isBefore(from) && !ts.isAfter(now)));
        }
        return new FeatureVector(aggs);
    }

    private static WindowAgg sum(List<Transaction> txs, Predicate<Instant> in) {
        WindowAgg acc = WindowAgg.EMPTY;
        for (Transaction t : txs) {
            if (in.test(t.ts())) {
                acc = acc.plus(WindowAgg.of(t.amountCents()));
            }
        }
        return acc;
    }
}
