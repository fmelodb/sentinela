package sentinela.adapter.oracle;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import sentinela.domain.Unit;
import sentinela.domain.WindowCatalog;

/** Conversões entre o domínio e as colunas: TIMESTAMP em UTC, DATE como dia civil, NUMBER(14,2). */
final class OracleTime {
    private OracleTime() {}

    static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    static Instant instant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime sliceStart(Unit unit, long index) {
        if (unit == Unit.DAY) {
            throw new IllegalArgumentException("dia não é TIMESTAMP; use day()");
        }
        return utc(WindowCatalog.sliceStart(unit, index, ZoneOffset.UTC));
    }

    static LocalDate day(long epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }

    static LocalDateTime dayStartUtc(long epochDay, ZoneId zone) {
        return utc(WindowCatalog.sliceStart(Unit.DAY, epochDay, zone));
    }

    static long cents(BigDecimal v) {
        return v.movePointRight(2).longValueExact();
    }

    static BigDecimal amount(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
