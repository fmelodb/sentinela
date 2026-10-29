package sentinela.workload;

import java.util.SplittableRandom;

/** Valores lognormais com mediana R$ 150 (μ = ln 150, σ = 1,2), em centavos, limitados a [R$ 1; R$ 50.000]. */
public final class AmountSampler {
    public static final long MIN_CENTS = 100;
    public static final long MAX_CENTS = 5_000_000;
    private static final double MU = Math.log(150.0);
    private static final double SIGMA = 1.2;

    private final SplittableRandom rnd;

    public AmountSampler(SplittableRandom rnd) {
        this.rnd = rnd;
    }

    public long nextCents() {
        double reais = Math.exp(MU + SIGMA * rnd.nextGaussian());
        return Math.clamp(Math.round(reais * 100), MIN_CENTS, MAX_CENTS);
    }
}
