package sentinela.domain;

/** Limites de uma janela em índices epoch da unidade, ambos inclusivos. */
public record Bounds(Unit unit, long lo, long hi) {
    public boolean contains(Unit u, long index) {
        return u == unit && index >= lo && index <= hi;
    }
}
