package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnvTest {
    @Test
    void defaultsZoneToSaoPaulo() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), Env.from(Map.of()).zone());
    }

    @Test
    void rejectsInvalidZone() {
        assertThrows(UsageException.class, () -> Env.from(Map.of("FS_ZONE", "Marte/Olimpo")));
    }

    @Test
    void requireOracleListsMissingVariables() {
        Env env = Env.from(Map.of("ORACLE_URL", "jdbc:oracle:thin:@//h:1521/S"));
        UsageException e = assertThrows(UsageException.class, env::requireOracle);
        assertTrue(e.getMessage().contains("ORACLE_USER"));
        assertTrue(e.getMessage().contains("ORACLE_PASSWORD"));
        assertFalse(e.getMessage().contains("ORACLE_URL"));
    }

    @Test
    void requireValkeyFailsWhenAbsentOrBlank() {
        assertThrows(UsageException.class, () -> Env.from(Map.of("VALKEY_URL", " ")).requireValkey());
        Env.from(Map.of("VALKEY_URL", "redis://localhost:6379")).requireValkey();
    }

    @Test
    void toStringHidesPassword() {
        Env env = Env.from(Map.of("ORACLE_PASSWORD", "segredo123"));
        assertFalse(env.toString().contains("segredo123"));
    }
}
