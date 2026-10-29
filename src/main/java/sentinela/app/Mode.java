package sentinela.app;

import sentinela.infra.UsageException;

public enum Mode {
    ORACLE("oracle"),
    ORACLE_VALKEY("oracle+valkey");

    private final String cli;

    Mode(String cli) {
        this.cli = cli;
    }

    public String cli() {
        return cli;
    }

    public static Mode parse(String s) {
        for (Mode m : values()) {
            if (m.cli.equals(s)) {
                return m;
            }
        }
        throw new UsageException("--mode precisa ser oracle ou oracle+valkey: " + s);
    }
}
