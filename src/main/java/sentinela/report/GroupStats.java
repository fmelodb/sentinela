package sentinela.report;

public record GroupStats(String run, long startMs, String program, String mode, String operation,
        long ops, long errors, double errorRate, double durationS, int targetTps, double achievedTps,
        double deviation, long minUs, double avgUs, long p95Us, long p99Us, long maxUs,
        boolean tpsOk, boolean errorOk) {}
