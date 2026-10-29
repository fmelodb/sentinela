package sentinela.app;

import sentinela.domain.FeatureStorePort;

/** Um destino de ingestão e o nome da operação no log (ingest_oracle, ingest_valkey). */
public record NamedSink(String operation, FeatureStorePort port) {}
