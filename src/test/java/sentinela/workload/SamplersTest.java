package sentinela.workload;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;
import sentinela.workload.AccountSampler.Distribution;

class SamplersTest {
    private static final int N = 200_000;

    @Test
    void sameSeedAndPurposeGiveSameSequence() {
        AccountSampler a = new AccountSampler(1000, Distribution.SKEWED, Seeds.stream(42, "x"));
        AccountSampler b = new AccountSampler(1000, Distribution.SKEWED, Seeds.stream(42, "x"));
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.next(), b.next());
        }
    }

    @Test
    void differentPurposesGiveDifferentSequences() {
        AccountSampler a = new AccountSampler(1_000_000, Distribution.UNIFORM, Seeds.stream(42, "a"));
        AccountSampler b = new AccountSampler(1_000_000, Distribution.UNIFORM, Seeds.stream(42, "b"));
        int equal = 0;
        for (int i = 0; i < 1000; i++) {
            if (a.next() == b.next()) {
                equal++;
            }
        }
        assertTrue(equal < 5, "sequências deveriam ser independentes: " + equal);
    }

    @Test
    void skewedPutsAboutHalfOfDrawsInHotAccounts() {
        AccountSampler s = new AccountSampler(100_000, Distribution.SKEWED, Seeds.stream(7, "t"));
        assertEquals(1000, s.hotAccounts());
        int hot = 0;
        for (int i = 0; i < N; i++) {
            long acc = s.next();
            assertTrue(acc >= 1 && acc <= 100_000);
            if (acc <= s.hotAccounts()) {
                hot++;
            }
        }
        double share = hot / (double) N; // esperado 0,5 + 0,5 × 1% = 0,505
        assertTrue(share > 0.49 && share < 0.52, "fração nas quentes: " + share);
    }

    @Test
    void uniformSpreadsDraws() {
        AccountSampler s = new AccountSampler(100_000, Distribution.UNIFORM, Seeds.stream(7, "t"));
        int hot = 0;
        for (int i = 0; i < N; i++) {
            if (s.next() <= 1000) {
                hot++;
            }
        }
        double share = hot / (double) N;
        assertTrue(share > 0.008 && share < 0.012, "fração nas 1% primeiras: " + share);
    }

    @Test
    void tinyUniverseStillHasOneHotAccount() {
        AccountSampler s = new AccountSampler(5, Distribution.SKEWED, Seeds.stream(1, "t"));
        assertEquals(1, s.hotAccounts());
        for (int i = 0; i < 100; i++) {
            long acc = s.next();
            assertTrue(acc >= 1 && acc <= 5);
        }
    }

    @Test
    void parsesDistribution() {
        assertEquals(Distribution.UNIFORM, Distribution.parse("uniform"));
        assertEquals(Distribution.SKEWED, Distribution.parse("skewed"));
        assertThrows(UsageException.class, () -> Distribution.parse("zipf"));
    }

    @Test
    void amountsAreBoundedWithMedianNear150Reais() {
        AmountSampler s = new AmountSampler(Seeds.stream(42, "v"));
        long[] v = new long[N];
        for (int i = 0; i < N; i++) {
            v[i] = s.nextCents();
            assertTrue(v[i] >= AmountSampler.MIN_CENTS && v[i] <= AmountSampler.MAX_CENTS, "fora da faixa: " + v[i]);
        }
        Arrays.sort(v);
        long median = v[N / 2];
        assertTrue(median > 13_500 && median < 16_500, "mediana em centavos: " + median);
    }
}
