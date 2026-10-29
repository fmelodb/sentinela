package sentinela.domain;

import java.time.Instant;

/** O único contrato que o domínio conhece. Implementações nunca leem o relógio. */
public interface FeatureStorePort {
    void ingest(Transaction tx);

    FeatureVector read(long accountId, Instant now);
}
