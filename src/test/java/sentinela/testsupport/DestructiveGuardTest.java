package sentinela.testsupport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import sentinela.infra.Env;

class DestructiveGuardTest {
    private static Env env(String oracleUrl, String valkeyUrl) {
        return new Env(oracleUrl, "u", "p", valkeyUrl, Env.from(Map.of()).zone());
    }

    @Test
    void allowsLocalPod() {
        assertTrue(DestructiveGuard.allowed(
                env("jdbc:oracle:thin:@//localhost:1521/SENTINELA", "redis://localhost:6379"), Map.of()));
        assertTrue(DestructiveGuard.allowed(env("jdbc:oracle:thin:@//127.0.0.1:1521/X", null), Map.of()));
    }

    @Test
    void refusesRemoteStoresWithoutOptIn() {
        assertFalse(DestructiveGuard.allowed(
                env("jdbc:oracle:thin:@//adb.sa-saopaulo-1.oraclecloud.com:1522/x_high", "redis://localhost:6379"), Map.of()));
        assertFalse(DestructiveGuard.allowed(
                env("jdbc:oracle:thin:@//localhost:1521/S", "rediss://cache.oci.example:6379"), Map.of()));
        assertFalse(DestructiveGuard.allowed(env("jdbc:oracle:thin:@prod_high?TNS_ADMIN=/w", null), Map.of()));
    }

    @Test
    void explicitOptInAllowsRemote() {
        assertTrue(DestructiveGuard.allowed(
                env("jdbc:oracle:thin:@//adb.example.com:1522/x", null), Map.of("FS_IT_DESTRUCTIVE", "1")));
    }
}
