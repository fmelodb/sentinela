package sentinela.domain;

import java.time.Instant;

/** Transação com instante em precisão de microssegundo (TIMESTAMP(6) no Oracle). */
public record Transaction(long txId, long accountId, Instant ts, long amountCents) {
    public Transaction {
        if (accountId <= 0) {
            throw new IllegalArgumentException("account_id precisa ser positivo: " + accountId);
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amount precisa ser positivo: " + amountCents);
        }
        if (ts.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("ts com precisão abaixo de microssegundo: " + ts);
        }
    }
}
