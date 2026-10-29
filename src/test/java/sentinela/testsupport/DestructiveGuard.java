package sentinela.testsupport;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sentinela.infra.Env;

/**
 * Testes de integração destrutivos (TRUNCATE, FLUSHDB) só rodam contra o pod local,
 * a menos que FS_IT_DESTRUCTIVE=1 libere explicitamente outro ambiente.
 */
public final class DestructiveGuard {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1");
    private static final Pattern JDBC_HOST = Pattern.compile("@//([^:/]+)");

    private DestructiveGuard() {}

    public static void assumeAllowed(Env env) {
        assumeTrue(allowed(env, System.getenv()),
                "teste destrutivo pulado: stores fora de localhost (defina FS_IT_DESTRUCTIVE=1 para forçar)");
    }

    static boolean allowed(Env env, Map<String, String> vars) {
        if ("1".equals(vars.get("FS_IT_DESTRUCTIVE"))) {
            return true;
        }
        if (env.oracleUrl() != null) {
            Matcher m = JDBC_HOST.matcher(env.oracleUrl());
            if (!m.find() || !LOCAL_HOSTS.contains(m.group(1))) {
                return false;
            }
        }
        return env.valkeyUrl() == null || LOCAL_HOSTS.contains(URI.create(env.valkeyUrl()).getHost());
    }
}
