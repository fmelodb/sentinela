package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ErrorReporterTest {
    @Test
    void countsAllAndPrintsOnlyFirstTenTraces() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ErrorReporter r = new ErrorReporter(new PrintStream(buf, true, StandardCharsets.UTF_8))) {
            for (int i = 0; i < 25; i++) {
                r.report(new IllegalStateException("falha " + i));
            }
            assertEquals(25, r.total());
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("falha 9"));
        assertFalse(out.contains("falha 10"));
        assertTrue(out.contains("total de erros: 25"));
        assertTrue(out.contains("IllegalStateException"));
    }
}
