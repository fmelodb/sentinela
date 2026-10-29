package sentinela.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Os 19 agregados de uma leitura. values() expande para os 95 valores na ordem da spec. */
public record FeatureVector(List<WindowAgg> aggs) {
    private static final List<String> METRICS = List.of("cnt", "sum", "min", "max", "avg");
    private static final List<String> NAMES = buildNames();
    private static final FeatureVector EMPTY =
            new FeatureVector(Collections.nCopies(WindowCatalog.SIZE, WindowAgg.EMPTY));

    public FeatureVector {
        if (aggs.size() != WindowCatalog.SIZE) {
            throw new IllegalArgumentException("vetor precisa de " + WindowCatalog.SIZE + " janelas: " + aggs.size());
        }
        aggs = List.copyOf(aggs);
    }

    public static FeatureVector empty() {
        return EMPTY;
    }

    public static List<String> names() {
        return NAMES;
    }

    public WindowAgg agg(int window) {
        return aggs.get(window);
    }

    /** 95 valores; min, max e avg são nulos quando cnt = 0. */
    public List<BigDecimal> values() {
        List<BigDecimal> out = new ArrayList<>(NAMES.size());
        for (WindowAgg a : aggs) {
            out.add(BigDecimal.valueOf(a.cnt()));
            out.add(BigDecimal.valueOf(a.sumCents(), 2));
            out.add(a.minCents() == null ? null : BigDecimal.valueOf(a.minCents(), 2));
            out.add(a.maxCents() == null ? null : BigDecimal.valueOf(a.maxCents(), 2));
            out.add(a.cnt() == 0 ? null
                    : BigDecimal.valueOf(a.sumCents())
                            .divide(BigDecimal.valueOf(a.cnt() * 100), 4, RoundingMode.HALF_EVEN));
        }
        return Collections.unmodifiableList(out);
    }

    private static List<String> buildNames() {
        List<String> names = new ArrayList<>();
        for (String suffix : WindowCatalog.SUFFIXES) {
            for (String m : METRICS) {
                names.add(m + "_" + suffix);
            }
        }
        return List.copyOf(names);
    }
}
