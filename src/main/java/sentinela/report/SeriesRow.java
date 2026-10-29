package sentinela.report;

public record SeriesRow(String run, String program, String mode, String operation, long second, long ops,
        boolean warmup) {}
