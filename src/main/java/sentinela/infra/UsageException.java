package sentinela.infra;

/** Argumento de linha de comando ou variável de ambiente inválida. Vira exit code 2. */
public final class UsageException extends RuntimeException {
    public UsageException(String message) {
        super(message);
    }
}
