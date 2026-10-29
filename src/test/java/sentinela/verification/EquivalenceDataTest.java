package sentinela.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;

class EquivalenceDataTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30.123456Z");

    private final List<Transaction> txs = EquivalenceData.generate(200, 42, NOW, SP);

    @Test
    void isDeterministicSortedAndOnlyBoundariesReachTheFuture() {
        assertEquals(txs, EquivalenceData.generate(200, 42, NOW, SP));
        for (int i = 1; i < txs.size(); i++) {
            assertFalse(txs.get(i).ts().isBefore(txs.get(i - 1).ts()), "ordem por ts");
        }
        List<Transaction> future = txs.stream().filter(t -> t.ts().isAfter(NOW)).toList();
        assertFalse(future.isEmpty(), "a classe de fronteiras tem uma transação 1 µs depois de now");
        assertTrue(future.stream().allMatch(t -> EquivalenceData.accountClass(t.accountId()) == EquivalenceData.BOUNDARIES
                && t.ts().equals(NOW.plusNanos(1_000))));
        assertTrue(txs.stream().allMatch(t -> t.accountId() >= 1 && t.accountId() <= 200));
    }

    @Test
    void silentAccountsHaveNothingInTheLastHour() {
        Instant hourAgo = NOW.minus(1, ChronoUnit.HOURS);
        List<Transaction> silent = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 1).toList();
        assertFalse(silent.isEmpty());
        assertTrue(silent.stream().noneMatch(t -> t.ts().isAfter(hourAgo)));
    }

    @Test
    void todayOnlyAccountsTransactOnlyTodayUntilNow() {
        long today = WindowCatalog.epochDay(NOW, SP);
        List<Transaction> todayOnly = txs.stream().filter(t -> EquivalenceData.accountClass(t.accountId()) == 2).toList();
        assertFalse(todayOnly.isEmpty());
        assertTrue(todayOnly.stream().allMatch(t -> WindowCatalog.epochDay(t.ts(), SP) == today && !t.ts().isAfter(NOW)));
    }

    @Test
    void boundaryAccountsHitEveryLimitOnBothSides() {
        List<Instant> b = EquivalenceData.boundaries(NOW, SP);
        Instant lo5m = NOW.minus(5, ChronoUnit.MINUTES);
        assertTrue(b.contains(lo5m));
        assertTrue(b.contains(lo5m.plusNanos(1_000)));
        assertTrue(b.contains(NOW));
        assertTrue(b.contains(NOW.plusNanos(1_000)));
        assertTrue(b.contains(Instant.parse("2026-10-29T03:00:00Z")), "00:00 local de hoje");
        assertTrue(b.contains(Instant.parse("2026-10-29T02:59:59.999999Z")));
        assertTrue(b.contains(Instant.parse("2026-10-23T03:00:00Z")), "início do primeiro dia fechado da 7d");
        assertTrue(b.contains(Instant.parse("2026-10-29T00:00:00Z")), "00:00 UTC");
        List<Instant> ofAccount3 = txs.stream().filter(t -> t.accountId() == 3).map(Transaction::ts).toList();
        assertTrue(ofAccount3.containsAll(b));
    }
}
