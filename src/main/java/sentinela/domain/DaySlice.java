package sentinela.domain;

/** Agregado de um dia civil fechado em FS_ZONE, pelo epoch day. */
public record DaySlice(long epochDay, WindowAgg agg) {}
