package sentinela.adapter.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import sentinela.domain.WindowCatalog;

class OracleReadSqlTest {
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant NOW = Instant.parse("2026-10-29T15:07:30Z");

    @Test
    void placeholderCountMatchesBinds() {
        long placeholders = OracleReadSql.SQL.chars().filter(c -> c == '?').count();
        Object[] binds = OracleReadSql.binds(42, WindowCatalog.bounds(NOW, SP));
        assertEquals(25, binds.length);
        assertEquals(binds.length, placeholders);
    }

    @Test
    void selectsFourColumnsPerWindowInOrder() {
        String sql = OracleReadSql.SQL;
        assertEquals(19, sql.split(" AS cnt_", -1).length - 1);
        assertTrue(sql.indexOf("AS cnt_1m") < sql.indexOf("AS max_72h"));
        assertTrue(sql.indexOf("AS max_72h") < sql.indexOf("AS cnt_7d"));
        assertTrue(sql.contains("AS max_92d"));
        assertTrue(sql.startsWith("WITH b AS (SELECT ? AS lo_1m"));
    }

    @Test
    void bindsFollowWindowBounds() {
        Object[] b = OracleReadSql.binds(42, WindowCatalog.bounds(NOW, SP));
        assertEquals(LocalDateTime.parse("2026-10-29T15:07:00"), b[0], "lo_1m = minuto corrente");
        assertEquals(LocalDateTime.parse("2026-10-29T15:03:00"), b[1], "lo_5m");
        assertEquals(LocalDateTime.parse("2026-10-29T14:08:00"), b[5], "lo_1h = 59 minutos antes");
        assertEquals(LocalDateTime.parse("2026-10-29T14:00:00"), b[6], "lo_2h = hora anterior");
        assertEquals(LocalDateTime.parse("2026-10-26T16:00:00"), b[14], "lo_72h");
        assertEquals(LocalDate.parse("2026-10-22"), b[15], "lo_7d");
        assertEquals(LocalDate.parse("2026-07-29"), b[18], "lo_92d");
        assertEquals(42L, b[19]);
        assertEquals(LocalDateTime.parse("2026-10-26T16:00:00"), b[20], "faixa de TX começa em lo_72h");
        assertEquals(LocalDateTime.parse("2026-10-29T15:08:00"), b[21], "fim do minuto corrente, exclusivo");
        assertEquals(42L, b[22]);
        assertEquals(LocalDate.parse("2026-07-29"), b[23]);
        assertEquals(LocalDate.parse("2026-10-29"), b[24], "hoje, exclusivo");
    }
}
