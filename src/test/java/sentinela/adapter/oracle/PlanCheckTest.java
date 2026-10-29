package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanCheckTest {
    private static final List<String> GOOD = List.of(
            "| Id  | Operation                   | Name             | Pstart| Pstop |",
            "|   0 | SELECT STATEMENT            |                  |       |       |",
            "|   1 |  NESTED LOOPS               |                  |       |       |",
            "|   4 |     PARTITION RANGE ITERATOR|                  |   KEY |   KEY |",
            "|   5 |      INDEX RANGE SCAN       | TX_ACC_TS_AMT_IX |   KEY |   KEY |",
            "|   8 |     INDEX RANGE SCAN        | DAILY_AGG_PK     |       |       |",
            "|   9 |      FAST DUAL              |                  |       |       |");

    @Test
    void expectedPlanHasNoProblems() {
        assertEquals(List.of(), PlanCheck.problems(GOOD));
    }

    @Test
    void flagsTableAccessAndMissingIndexOrPruning() {
        List<String> bad = List.of(
                "|   4 |     PARTITION RANGE ALL     |                  |     1 |1048575|",
                "|   5 |      TABLE ACCESS FULL      | TX               |     1 |1048575|",
                "|   8 |     INDEX RANGE SCAN        | DAILY_AGG_PK     |       |       |");
        List<String> problems = PlanCheck.problems(bad);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("TABLE ACCESS")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("TX_ACC_TS_AMT_IX")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("PARTITION RANGE ITERATOR")));
    }
}
