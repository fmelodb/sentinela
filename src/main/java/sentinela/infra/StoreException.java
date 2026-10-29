package sentinela.infra;

/** Falha de uma operação contra um store durante a execução. Vira status "error" no log. */
public final class StoreException extends RuntimeException {
    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
