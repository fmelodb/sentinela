package sentinela.domain;

/** Uma fatia agregada: unidade, índice epoch da unidade e métricas. */
public record Slice(Unit unit, long index, WindowAgg agg) {}
