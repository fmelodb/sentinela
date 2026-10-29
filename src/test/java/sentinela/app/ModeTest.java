package sentinela.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import sentinela.infra.UsageException;

class ModeTest {
    @Test
    void parsesCliNames() {
        assertEquals(Mode.ORACLE, Mode.parse("oracle"));
        assertEquals(Mode.ORACLE_VALKEY, Mode.parse("oracle+valkey"));
        assertEquals("oracle+valkey", Mode.ORACLE_VALKEY.cli());
        assertThrows(UsageException.class, () -> Mode.parse("valkey"));
    }
}
