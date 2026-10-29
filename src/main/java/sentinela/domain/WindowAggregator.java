package sentinela.domain;

import java.util.Arrays;

/**
 * Combina as transações recentes e as fatias de dias fechados nas 19 janelas (spec técnica 4.4).
 * Transações e fatias fora de qualquer limite, inclusive futuras, são ignoradas.
 */
public final class WindowAggregator {
    private WindowAggregator() {}

    public static FeatureVector aggregate(Iterable<Transaction> recent, Iterable<DaySlice> days, Limits l) {
        int rolling = WindowCatalog.ROLLING.size();
        WindowAgg[] acc = new WindowAgg[WindowCatalog.SIZE];
        Arrays.fill(acc, WindowAgg.EMPTY);
        WindowAgg today = WindowAgg.EMPTY;
        for (Transaction t : recent) {
            WindowAgg one = WindowAgg.of(t.amountCents());
            for (int i = 0; i < rolling; i++) {
                if (l.inRolling(i, t.ts())) {
                    acc[i] = acc[i].plus(one);
                }
            }
            if (l.inToday(t.ts())) {
                today = today.plus(one);
            }
        }
        for (DaySlice d : days) {
            for (int j = 0; j < WindowCatalog.DAYS.size(); j++) {
                if (l.inClosed(j, d.epochDay())) {
                    acc[rolling + j] = acc[rolling + j].plus(d.agg());
                }
            }
        }
        for (int j = 0; j < WindowCatalog.DAYS.size(); j++) {
            acc[rolling + j] = acc[rolling + j].plus(today);
        }
        return new FeatureVector(Arrays.asList(acc));
    }
}
