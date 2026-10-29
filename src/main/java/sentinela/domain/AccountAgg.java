package sentinela.domain;

/** Agregado de uma conta num dia (linha de DAILY_AGG). */
public record AccountAgg(long accountId, WindowAgg agg) {}
