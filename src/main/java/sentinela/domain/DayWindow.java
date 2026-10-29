package sentinela.domain;

/** Janela de dias ("7d"): os n − 1 dias fechados anteriores a hoje mais o dia corrente até now. */
public record DayWindow(String suffix, int n) {}
