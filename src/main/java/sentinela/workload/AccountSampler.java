package sentinela.workload;

import java.util.SplittableRandom;
import sentinela.infra.UsageException;

/**
 * Sorteia contas em [1, N]. skewed: com probabilidade 0,5 sorteia entre as contas quentes
 * [1, max(1, N/100)]; senão, uniforme em [1, N]. Cerca de 50% das transações caem em 1% das contas.
 */
public final class AccountSampler {
    public enum Distribution {
        UNIFORM,
        SKEWED;

        public static Distribution parse(String s) {
            return switch (s) {
                case "uniform" -> UNIFORM;
                case "skewed" -> SKEWED;
                default -> throw new UsageException("--distribution precisa ser uniform ou skewed: " + s);
            };
        }
    }

    private final long accounts;
    private final long hot;
    private final Distribution distribution;
    private final SplittableRandom rnd;

    public AccountSampler(long accounts, Distribution distribution, SplittableRandom rnd) {
        if (accounts <= 0) {
            throw new IllegalArgumentException("universo de contas vazio: " + accounts);
        }
        this.accounts = accounts;
        this.hot = Math.max(1, accounts / 100);
        this.distribution = distribution;
        this.rnd = rnd;
    }

    public long next() {
        if (distribution == Distribution.SKEWED && rnd.nextBoolean()) {
            return 1 + rnd.nextLong(hot);
        }
        return 1 + rnd.nextLong(accounts);
    }

    public long hotAccounts() {
        return hot;
    }
}
