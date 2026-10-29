package sentinela.domain;

/** Fatia de uma conta específica, usada para reconstruir o Valkey a partir do Oracle. */
public record KeyedSlice(long accountId, Slice slice) {}
