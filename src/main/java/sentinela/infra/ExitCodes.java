package sentinela.infra;

public final class ExitCodes {
    public static final int OK = 0;
    public static final int VERIFICATION_FAILED = 1;
    public static final int USAGE = 2;
    public static final int STORE_UNAVAILABLE = 3;
    public static final int INVALID_RUN = 4;
    public static final int UNEXPECTED = 5;

    private ExitCodes() {}
}
