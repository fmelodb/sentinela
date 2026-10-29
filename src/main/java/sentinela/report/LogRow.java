package sentinela.report;

public record LogRow(long tsMs, String program, String mode, String operation, long accountId, long latencyUs,
        boolean ok) {}
