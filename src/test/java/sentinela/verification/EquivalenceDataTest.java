package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.Transaction;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;

class EquivalenceDataTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30.123456Z");

    private final List<Transaction> txs = EquivalenceData.generate(200, 42, NOW, SP);

    @Test
    void isDeterministicSortedAndNeverInTheFuture() {
        assertEquals(txs, EquivalenceData.generate(200, 42, NOW, SP));
        for (int i = 1; i < txs.size(); i++) {
            assertFalse(txs.get(i).ts().isBefore(txs.get(i - 1).ts()), "ordem por ts");
        }
        assertTrue(txs.stream().noneMatch(t -> t.ts().isAfter(NOW)));
        assertTrue(txs.stream().allMatch(t -> t.accountId() >= 1 && t.accountId() <= 200));
    }

    @Test
    void silentAccountsHaveNothingInTheLastHourSlices() {
        Instant lastHour = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(NOW) - 59, SP);
        List<Transaction> silent = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 1).toList();
        assertFalse(silent.isEmpty());
        assertTrue(silent.stream().allMatch(t -> t.ts().isBefore(lastHour)));
    }

    @Test
    void todayOnlyAccountsTransactOnlyToday() {
        long today = WindowCatalog.epochDay(NOW, SP);
        List<Transaction> todayOnly = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 2).toList();
        assertFalse(todayOnly.isEmpty());
        assertTrue(todayOnly.stream().allMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == today));
    }

    @Test
    void boundaryAccountsHitEveryWindowLowerBound() {
        List<Instant> b = EquivalenceData.boundaries(NOW, SP);
        Instant lo5m = WindowCatalog.sliceStart(Unit.MINUTE, WindowCatalog.epochMinute(NOW) - 4, SP);
        assertTrue(b.contains(lo5m));
        assertTrue(b.contains(lo5m.minusNanos(1_000)));
        assertTrue(b.contains(Instant.parse("2026-10-29T03:00:00Z")), "00:00 local");
        assertTrue(b.contains(Instant.parse("2026-10-29T00:00:00Z")), "00:00 UTC");
        List<Instant> ofAccount3 = txs.stream().filter(t -> t.accountId() == 3).map(Transaction::ts).toList();
        assertTrue(ofAccount3.containsAll(b));
    }
}
