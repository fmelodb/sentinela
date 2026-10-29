package sentinela.verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.Window;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

/**
 * Cálculo direto das 19 janelas sobre a lista de transações, sem fatias e sem WindowCatalog.bounds.
 * Serve de terceiro voto: se Oracle e Valkey errarem do mesmo jeito, a referência discorda.
 */
public final class ReferenceModel {
    private ReferenceModel() {}

    public static FeatureVector compute(List<Transaction> txs, Instant now, ZoneId zone) {
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        Instant hourStart = now.truncatedTo(ChronoUnit.HOURS);
        Instant upper = minuteStart.plus(1, ChronoUnit.MINUTES);
        LocalDate today = LocalDate.ofInstant(now, zone);
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        List<WindowAgg> aggs = new ArrayList<>(WindowCatalog.SIZE);
        for (Window w : WindowCatalog.WINDOWS) {
            Instant from;
            Instant to;
            switch (w.unit()) {
                case MINUTE -> {
                    from = minuteStart.minus(w.n() - 1, ChronoUnit.MINUTES);
                    to = upper;
                }
                case HOUR -> {
                    from = hourStart.minus(w.n() - 1, ChronoUnit.HOURS);
                    to = upper;
                }
                default -> {
                    from = today.minusDays(w.n()).atStartOfDay(zone).toInstant();
                    to = todayStart;
                }
            }
            WindowAgg acc = WindowAgg.EMPTY;
            for (Transaction t : txs) {
                if (!t.ts().isBefore(from) && t.ts().isBefore(to)) {
                    acc = acc.plus(WindowAgg.of(t.amountCents()));
                }
            }
            aggs.add(acc);
        }
        return new FeatureVector(aggs);
    }
}
