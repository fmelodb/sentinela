package sentinela.infra;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Conexões vêm do ambiente para não aparecerem em logs nem em argumentos. */
public record Env(String oracleUrl, String oracleUser, String oraclePassword, String valkeyUrl, ZoneId zone) {
    public static final String DEFAULT_ZONE = "America/Sao_Paulo";

    public static Env fromSystem() {
        return from(System.getenv());
    }

    public static Env from(Map<String, String> vars) {
        String z = vars.getOrDefault("FS_ZONE", DEFAULT_ZONE);
        ZoneId zone;
        try {
            zone = ZoneId.of(z);
        } catch (DateTimeException e) {
            throw new UsageException("FS_ZONE inválido: " + z);
        }
        return new Env(clean(vars.get("ORACLE_URL")), clean(vars.get("ORACLE_USER")),
                clean(vars.get("ORACLE_PASSWORD")), clean(vars.get("VALKEY_URL")), zone);
    }

    public void requireOracle() {
        List<String> missing = new ArrayList<>();
        if (oracleUrl == null) {
            missing.add("ORACLE_URL");
        }
        if (oracleUser == null) {
            missing.add("ORACLE_USER");
        }
        if (oraclePassword == null) {
            missing.add("ORACLE_PASSWORD");
        }
        if (!missing.isEmpty()) {
            throw new UsageException("variáveis de ambiente ausentes: " + String.join(", ", missing));
        }
    }

    public void requireValkey() {
        if (valkeyUrl == null) {
            throw new UsageException("variável de ambiente ausente: VALKEY_URL (obrigatória no modo oracle+valkey)");
        }
    }

    @Override
    public String toString() {
        return "Env[oracleUrl=" + oracleUrl + ", oracleUser=" + oracleUser + ", oraclePassword=***, valkeyUrl="
                + valkeyUrl + ", zone=" + zone + "]";
    }

    private static String clean(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
