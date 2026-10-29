package sentinela.domain;

import java.time.Duration;

/** Janela deslizante ("5m", "24h"): transações com now − span < ts ≤ now. */
public record RollingWindow(String suffix, Duration span) {}
