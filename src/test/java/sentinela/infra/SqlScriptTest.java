package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlScriptTest {
    @Test
    void splitsOnTrailingSemicolonAndDropsCommentLines() {
        String script = "-- cabeçalho\nCREATE TABLE a (\n  x NUMBER\n);\n\n-- outro\nCREATE INDEX i ON a (x);\n";
        assertEquals(List.of("CREATE TABLE a (\n  x NUMBER\n)", "CREATE INDEX i ON a (x)"), SqlScript.split(script));
    }

    @Test
    void keepsSemicolonInsideLine() {
        assertEquals(List.of("SELECT ';' FROM dual"), SqlScript.split("SELECT ';' FROM dual;\n"));
    }

    @Test
    void schemaResourceHasThreeStatements() throws Exception {
        try (var in = SqlScriptTest.class.getResourceAsStream("/db/01_schema.sql")) {
            assertNotNull(in, "db/01_schema.sql precisa estar no classpath");
            List<String> stmts = SqlScript.split(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(3, stmts.size());
            assertTrue(stmts.get(0).startsWith("CREATE TABLE IF NOT EXISTS tx"));
        }
    }
}
