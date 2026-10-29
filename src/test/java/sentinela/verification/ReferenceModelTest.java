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
import sentinela.domain.DaySlice;
import sentinela.domain.FeatureVector;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowAggregator;
import sentinela.domain.WindowCatalog;

class ReferenceModelTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40Z"); // 17:23:40 em São Paulo

    @Test
    void emptyHistoryGivesEmptyVector() {
        assertEquals(FeatureVector.empty(), ReferenceModel.compute(List.of(), NOW, SP));
    }

    @Test
    void handPickedTransactions() {
        List<Transaction> txs = List.of(
                new Transaction(1, 1, NOW.minusSeconds(10), 1000),
                new Transaction(2, 1, NOW.minus(2, ChronoUnit.HOURS), 2000), // exatamente no limite da 2h
                new Transaction(3, 1, Instant.parse("2026-10-29T02:59:59.999999Z"), 500), // 23:59:59 de 28/10 em SP
                new Transaction(4, 1, Instant.parse("2026-10-29T03:00:00Z"), 700)); // 00:00 de 29/10 em SP
        FeatureVector v = ReferenceModel.compute(txs, NOW, SP);
        assertEquals(WindowAgg.of(1000), v.agg(0), "1m");
        assertEquals(WindowAgg.of(1000), v.agg(6), "2h exclui exatamente now − 2h");
        assertEquals(new WindowAgg(2, 3000, 1000L, 2000L), v.agg(7), "3h");
        assertEquals(4, v.agg(12).cnt(), "24h");
        assertEquals(new WindowAgg(4, 4200, 500L, 2000L), v.agg(15), "7d: 28/10 fechado e hoje");
    }

    /** O domínio (limits + WindowAggregator) e a referência precisam concordar em dados aleatórios. */
    @Test
    void agreesWithDomainAggregation() {
        Instant now = Instant.parse("2026-10-29T20:23:40.123456Z");
        List<Transaction> txs = EquivalenceData.generate(400, 7, now, SP);
        Map<Long, List<Transaction>> byAccount = new HashMap<>();
        txs.forEach(t -> byAccount.computeIfAbsent(t.accountId(), k -> new ArrayList<>()).add(t));
        for (Map.Entry<Long, List<Transaction>> e : byAccount.entrySet()) {
            FeatureVector expected = ReferenceModel.compute(e.getValue(), now, SP);
            FeatureVector actual = WindowAggregator.aggregate(e.getValue(), daySlices(e.getValue()),
                    WindowCatalog.limits(now, SP));
            assertEquals(expected, actual, "conta " + e.getKey());
        }
    }

    private static List<DaySlice> daySlices(List<Transaction> txs) {
        Map<Long, WindowAgg> m = new HashMap<>();
        txs.forEach(t -> m.merge(WindowCatalog.epochDay(t.ts(), SP), WindowAgg.of(t.amountCents()), WindowAgg::plus));
        return m.entrySet().stream().map(e -> new DaySlice(e.getKey(), e.getValue())).toList();
    }
}
