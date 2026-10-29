package sentinela.app;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import sentinela.infra.Args;
import sentinela.infra.Env;
import sentinela.infra.ExitCodes;
import sentinela.infra.SqlScript;
import sentinela.infra.StoreUnavailableException;

/** Cria TX e DAILY_AGG no schema de ORACLE_USER. Idempotente. */
public final class SchemaApp {
    private SchemaApp() {}

    public static int run(String[] argv) throws Exception {
        Args.parse(argv, Map.of(), Set.of());
        Env env = Env.fromSystem();
        env.requireOracle();
        String script;
        try (InputStream in = SchemaApp.class.getResourceAsStream("/db/01_schema.sql")) {
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> statements = SqlScript.split(script);
        Connection c;
        try {
            c = DriverManager.getConnection(env.oracleUrl(), env.oracleUser(), env.oraclePassword());
        } catch (SQLException e) {
            throw new StoreUnavailableException("Oracle em " + env.oracleUrl(), e);
        }
        try (c; Statement st = c.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
                System.err.println("schema: ok - " + sql.lines().findFirst().orElse(sql));
            }
        }
        return ExitCodes.OK;
    }
}
