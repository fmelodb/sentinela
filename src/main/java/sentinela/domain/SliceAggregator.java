package sentinela.domain;

import java.util.Arrays;
import java.util.List;

/** Combina fatias nas janelas. Fatias fora de qualquer limite, inclusive futuras, são ignoradas. */
public final class SliceAggregator {
    private SliceAggregator() {}

    public static FeatureVector aggregate(Iterable<Slice> slices, List<Bounds> bounds) {
        WindowAgg[] acc = new WindowAgg[bounds.size()];
        Arrays.fill(acc, WindowAgg.EMPTY);
        for (Slice s : slices) {
            for (int i = 0; i < acc.length; i++) {
                if (bounds.get(i).contains(s.unit(), s.index())) {
                    acc[i] = acc[i].plus(s.agg());
                }
            }
        }
        return new FeatureVector(Arrays.asList(acc));
    }
}
