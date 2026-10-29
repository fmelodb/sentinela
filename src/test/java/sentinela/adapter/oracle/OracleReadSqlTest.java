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
    private static final Instant NOW = Instant.parse("2026-10-29T20:23:40.123456Z"); // 17:23:40 em São Paulo

    @Test
    void placeholderCountMatchesBinds() {
        long placeholders = OracleReadSql.SQL.chars().filter(c -> c == '?').count();
        Object[] binds = OracleReadSql.binds(42, WindowCatalog.limits(NOW, SP));
        assertEquals(26, binds.length);
        assertEquals(binds.length, placeholders);
    }

    @Test
    void selectsFourColumnsPerPartInOrder() {
        String sql = OracleReadSql.SQL;
        assertEquals(20, sql.split(" AS cnt_", -1).length - 1, "15 deslizantes, hoje e 4 de dias");
        assertTrue(sql.indexOf("AS cnt_1m") < sql.indexOf("AS max_72h"));
        assertTrue(sql.indexOf("AS max_72h") < sql.indexOf("AS cnt_hoje"));
        assertTrue(sql.indexOf("AS max_hoje") < sql.indexOf("AS cnt_7d"));
        assertTrue(sql.contains("AS max_92d"));
        assertTrue(sql.startsWith("WITH b AS (SELECT ? AS lo_1m"));
        assertTrue(sql.contains("x.ts > b.lo_5m"), "limite inferior exclusivo");
        assertTrue(sql.contains("x.ts >= b.lo_hoje"), "dia corrente desde 00:00, inclusivo");
        assertTrue(sql.contains("x.account_id = ? AND x.ts > ? AND x.ts <= ?"), "faixa (now − 72h, now]");
        assertTrue(sql.contains("a.account_id = ? AND a.dia >= ? AND a.dia < ?"));
    }

    @Test
    void bindsFollowLimits() {
        Object[] b = OracleReadSql.binds(42, WindowCatalog.limits(NOW, SP));
        assertEquals(LocalDateTime.parse("2026-10-29T20:22:40.123456"), b[0], "lo_1m");
        assertEquals(LocalDateTime.parse("2026-10-29T20:18:40.123456"), b[1], "lo_5m");
        assertEquals(LocalDateTime.parse("2026-10-29T19:23:40.123456"), b[5], "lo_1h");
        assertEquals(LocalDateTime.parse("2026-10-29T18:23:40.123456"), b[6], "lo_2h");
        assertEquals(LocalDateTime.parse("2026-10-26T20:23:40.123456"), b[14], "lo_72h");
        assertEquals(LocalDateTime.parse("2026-10-29T03:00:00"), b[15], "lo_hoje = 00:00 local em UTC");
        assertEquals(LocalDate.parse("2026-10-23"), b[16], "lo_7d");
        assertEquals(LocalDate.parse("2026-07-30"), b[19], "lo_92d");
        assertEquals(42L, b[20]);
        assertEquals(b[14], b[21], "faixa de TX começa em lo_72h, exclusivo");
        assertEquals(LocalDateTime.parse("2026-10-29T20:23:40.123456"), b[22], "now, inclusivo");
        assertEquals(42L, b[23]);
        assertEquals(LocalDate.parse("2026-07-30"), b[24]);
        assertEquals(LocalDate.parse("2026-10-29"), b[25], "hoje, exclusivo");
    }
}
