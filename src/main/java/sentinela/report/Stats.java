package sentinela.report;

public final class Stats {
    private Stats() {}

    /** Percentil nearest-rank, sem interpolação: posição ceil(q × n), base 1. permille: 950 = p95. */
    public static long nearestRank(long[] sorted, int permille) {
        int n = sorted.length;
        if (n == 0) {
            throw new IllegalArgumentException("amostra vazia");
        }
        long rank = ((long) permille * n + 999) / 1000;
        return sorted[(int) Math.max(1, rank) - 1];
    }
}
