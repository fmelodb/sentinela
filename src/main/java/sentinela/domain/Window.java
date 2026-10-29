package sentinela.domain;

/** Uma das 19 janelas: sufixo do nome ("5m", "24h", "31d"), unidade da fatia e quantidade de fatias. */
public record Window(String suffix, Unit unit, int n) {}
