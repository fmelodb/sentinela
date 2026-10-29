package sentinela.domain;

import java.time.Instant;
import java.util.List;

/**
 * Limites de uma leitura em now (spec técnica 4.2). Janela deslizante i: rollingLo[i] < ts ≤ now.
 * Dia corrente: todayStart ≤ ts ≤ now. Janela de dias j: dias fechados de closedLo[j] a today − 1, mais o dia corrente.
 */
public record Limits(Instant now, List<Instant> rollingLo, long today, Instant todayStart, List<Long> closedLo) {
    public Limits {
        rollingLo = List.copyOf(rollingLo);
        closedLo = List.copyOf(closedLo);
    }

    public boolean inRolling(int i, Instant ts) {
        return ts.isAfter(rollingLo.get(i)) && !ts.isAfter(now);
    }

    public boolean inToday(Instant ts) {
        return !ts.isBefore(todayStart) && !ts.isAfter(now);
    }

    public boolean inClosed(int j, long epochDay) {
        return epochDay >= closedLo.get(j) && epochDay < today;
    }

    /** Limite inferior da maior janela deslizante (72h): a faixa de transações que a leitura busca. */
    public Instant oldestRolling() {
        return rollingLo.get(rollingLo.size() - 1);
    }

    /** Primeiro dia fechado da maior janela de dias (92d). */
    public long oldestClosed() {
        return closedLo.get(closedLo.size() - 1);
    }
}
