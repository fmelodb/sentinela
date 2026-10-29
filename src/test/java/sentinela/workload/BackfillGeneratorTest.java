package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sentinela.domain.AccountAgg;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AccountSampler.Distribution;

class BackfillGeneratorTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z"); // 12:07:30 em São Paulo

    private static BackfillGenerator gen() {
        return new BackfillGenerator(1000, Distribution.SKEWED, 5, 42, SP, NOW);
    }

    @Test
    void recentTransactionsCoverThreeClosedDaysAndTodayUntilNow() {
        BackfillGenerator g = gen();
        Instant from = WindowCatalog.sliceStart(Unit.DAY, g.today() - BackfillGenerator.RECENT_DAYS, SP);
        List<Transaction> txs = new ArrayList<>();
        g.recentTransactions().forEachRemaining(txs::add);
        // 3 dias × 5.000 + 12h07m30s de hoje (≈ 50,5% de 5.000)
        assertTrue(txs.size() > 17_400 && txs.size() < 17_600, "quantidade: " + txs.size());
        Set<Long> ids = new HashSet<>();
        for (Transaction t : txs) {
            assertFalse(t.ts().isBefore(from), "antes de D-3: " + t.ts());
            assertTrue(t.ts().isBefore(NOW), "no futuro: " + t.ts());
            assertTrue(t.txId() > BackfillGenerator.TX_ID_BASE);
            assertTrue(t.accountId() >= 1 && t.accountId() <= 1000);
            assertTrue(ids.add(t.txId()), "tx_id repetido");
        }
        assertTrue(txs.stream().anyMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == g.today()), "inclui hoje");
    }

    @Test
    void syntheticDayAggregatesAllDrawsOfTheDay() {
        long day = gen().today() - 10;
        List<AccountAgg> rows = gen().syntheticDay(day);
        assertEquals(5_000, rows.stream().mapToLong(r -> r.agg().cnt()).sum());
        for (int i = 0; i < rows.size(); i++) {
            AccountAgg r = rows.get(i);
            assertTrue(r.agg().minCents() <= r.agg().maxCents());
            assertTrue(r.agg().sumCents() >= r.agg().maxCents());
            if (i > 0) {
                assertTrue(rows.get(i - 1).accountId() < r.accountId(), "ordenado por conta");
            }
        }
        assertEquals(rows, gen().syntheticDay(day), "determinístico");
        assertNotEquals(rows, gen().syntheticDay(day - 1), "dias diferentes, sorteios diferentes");
    }

    @Test
    void sameSeedGivesSameRecentTransactions() {
        Iterator<Transaction> a = gen().recentTransactions();
        Iterator<Transaction> b = gen().recentTransactions();
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.next(), b.next());
        }
    }
}
