package sentinela.infra;

/** Store inacessível na inicialização de um programa. Vira exit code 3. */
public final class StoreUnavailableException extends RuntimeException {
    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
