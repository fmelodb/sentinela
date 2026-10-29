package sentinela.workload;

import java.util.SplittableRandom;

/** Um fluxo pseudoaleatório independente por propósito, derivado do --seed. */
public final class Seeds {
    private Seeds() {}

    public static SplittableRandom stream(long seed, String purpose) {
        return new SplittableRandom(seed * 0x9E3779B97F4A7C15L ^ purpose.hashCode());
    }
}
