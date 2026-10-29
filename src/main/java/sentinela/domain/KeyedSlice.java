package sentinela.domain;

/** Fatia de dia de uma conta específica, usada para reconstruir o Valkey a partir do Oracle. */
public record KeyedSlice(long accountId, DaySlice slice) {}
