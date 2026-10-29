package sentinela.domain;

/** As quatro métricas decomponíveis de uma fatia ou janela, em centavos. min/max são nulos quando cnt = 0. */
public record WindowAgg(long cnt, long sumCents, Long minCents, Long maxCents) {
    public static final WindowAgg EMPTY = new WindowAgg(0, 0, null, null);

    public WindowAgg {
        if (cnt < 0) {
            throw new IllegalArgumentException("cnt negativo: " + cnt);
        }
        if (cnt == 0 && (sumCents != 0 || minCents != null || maxCents != null)) {
            throw new IllegalArgumentException("agregado vazio com valores: " + sumCents + "/" + minCents + "/" + maxCents);
        }
        if (cnt > 0 && (minCents == null || maxCents == null)) {
            throw new IllegalArgumentException("agregado com cnt > 0 sem min/max");
        }
    }

    public static WindowAgg of(long amountCents) {
        return new WindowAgg(1, amountCents, amountCents, amountCents);
    }

    public WindowAgg plus(WindowAgg o) {
        if (o.cnt == 0) {
            return this;
        }
        if (cnt == 0) {
            return o;
        }
        return new WindowAgg(cnt + o.cnt, sumCents + o.sumCents,
                Math.min(minCents, o.minCents), Math.max(maxCents, o.maxCents));
    }
}
