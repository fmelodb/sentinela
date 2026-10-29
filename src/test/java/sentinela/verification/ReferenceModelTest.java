package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sentinela.domain.FeatureVector;
import sentinela.domain.Slice;
import sentinela.domain.SliceAggregator;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;

class ReferenceModelTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");

    @Test
    void emptyHistoryGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), ReferenceModel.compute(List.of(), NOW, SP));
    }

    @Test
    void handPickedTransactions() {
        List<Transaction> txs = List.of(
                new Transaction(1, 1, NOW.minusSeconds(10), 1000),
                new Transaction(2, 1, NOW.minus(2, ChronoUnit.HOURS), 2000),
                new Transaction(3, 1, Instant.parse("2026-10-29T02:59:59.999999Z"), 500), // 23:59 de 28/10 em SP
                new Transaction(4, 1, Instant.parse("2026-10-29T03:00:00Z"), 700)); // 00:00 de 29/10 em SP
        FeatureVector v = ReferenceModel.compute(txs, NOW, SP);
        assertEquals(WindowAgg.of(1000), v.agg(0));
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(WindowAgg.of(500), v.agg(15), "7d tem só o dia 28, não o 29");
        assertEquals(4, v.agg(12).cnt(), "24h em UTC alcança 28/10 16:00Z");
    }

    /** O domínio (WindowCatalog + SliceAggregator) e a referência precisam concordar em dados aleatórios. */
    @Test
    void agreesWithDomainSliceAggregation() {
        List<Transaction> txs = EquivalenceData.generate(400, 7, NOW, SP);
        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));
        for (Map.Entry<Long, List<Transaction>> e : byAccount.entrySet()) {
            FeatureVector expected = ReferenceModel.compute(e.getValue(), NOW, SP);
            FeatureVector actual = SliceAggregator.aggregate(slicesOf(e.getValue()), WindowCatalog.bounds(NOW, SP));
            assertEquals(expected, actual, "conta " + e.getKey());
        }
    }

    private static List<Slice> slicesOf(List<Transaction> txs) {
        Map<String, Slice> m = new HashMap<>();
        for (Transaction t : txs) {
            add(m, new Slice(Unit.MINUTE, WindowCatalog.epochMinute(t.ts()), WindowAgg.of(t.amountCents())));
            add(m, new Slice(Unit.HOUR, WindowCatalog.epochHour(t.ts()), WindowAgg.of(t.amountCents())));
            add(m, new Slice(Unit.DAY, WindowCatalog.epochDay(t.ts(), SP), WindowAgg.of(t.amountCents())));
        }
        return new ArrayList<>(m.values());
    }

    private static void add(Map<String, Slice> m, Slice s) {
        m.merge(s.unit() + ":" + s.index(), s, (a, b) -> new Slice(a.unit(), a.index(), a.agg().plus(b.agg())));
    }
}
