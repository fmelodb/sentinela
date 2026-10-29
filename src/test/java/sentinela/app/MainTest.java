package sentinela.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sentinela.infra.ExitCodes;

class MainTest {
    @Test
    void noArgumentsIsUsageError() {
        assertEquals(ExitCodes.USAGE, Main.run(new String[0]));
    }

    @Test
    void unknownProgramIsUsageError() {
        assertEquals(ExitCodes.USAGE, Main.run(new String[] {"nao-existe"}));
    }
}
