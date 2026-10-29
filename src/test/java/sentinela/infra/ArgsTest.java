package sentinela.infra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArgsTest {
    private static final Map<String, String> DEFAULTS =
            Args.defaults("tps", "600", "mode", "oracle", "log", null);

    @Test
    void usesDefaultsWhenAbsent() {
        Args a = Args.parse(new String[0], DEFAULTS, Set.of("reset"));
        assertEquals(600, a.integer("tps"));
        assertEquals("oracle", a.string("mode"));
        assertTrue(a.optional("log").isEmpty());
        assertFalse(a.flag("reset"));
    }

    @Test
    void overridesValuesAndFlags() {
        Args a = Args.parse(new String[] {"--tps", "50", "--reset", "--log", "x/y.csv"}, DEFAULTS, Set.of("reset"));
        assertEquals(50, a.positiveInt("tps"));
        assertTrue(a.flag("reset"));
        assertEquals(Path.of("x/y.csv"), a.path("log"));
    }

    @Test
    void rejectsUnknownArgument() {
        UsageException e = assertThrows(UsageException.class,
                () -> Args.parse(new String[] {"--nope", "1"}, DEFAULTS, Set.of()));
        assertTrue(e.getMessage().contains("--nope"));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(UsageException.class, () -> Args.parse(new String[] {"--tps"}, DEFAULTS, Set.of()));
    }

    @Test
    void rejectsPositionalArgument() {
        assertThrows(UsageException.class, () -> Args.parse(new String[] {"tps"}, DEFAULTS, Set.of()));
    }

    @Test
    void rejectsNonIntegerAndNonPositive() {
        Args bad = Args.parse(new String[] {"--tps", "abc"}, DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> bad.integer("tps"));
        Args zero = Args.parse(new String[] {"--tps", "0"}, DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> zero.positiveInt("tps"));
    }

    @Test
    void requiredWithoutDefaultFails() {
        Args a = Args.parse(new String[0], DEFAULTS, Set.of());
        assertThrows(UsageException.class, () -> a.string("log"));
    }
}
