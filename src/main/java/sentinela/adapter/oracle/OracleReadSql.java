package sentinela.adapter.oracle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import sentinela.domain.DayWindow;
import sentinela.domain.Limits;
import sentinela.domain.RollingWindow;
import sentinela.domain.WindowCatalog;

/**
 * Gera, a partir do WindowCatalog, o statement único da leitura (spec técnica 5.3): agregados condicionais
 * sobre TX para as 15 janelas deslizantes e o dia corrente, em CROSS JOIN com agregados sobre DAILY_AGG
 * para os dias fechados das 4 janelas de dias. Os limites entram como colunas da linha b; os predicados
 * de poda usam binds diretos.
 */
final class OracleReadSql {
    static final String TODAY = "hoje";
    static final String SQL = build();

    private OracleReadSql() {}

    static Object[] binds(long accountId, Limits l) {
        Object[] b = new Object[WindowCatalog.SIZE + 1 + 6];
        int i = 0;
        for (Instant lo : l.rollingLo()) {
            b[i++] = OracleTime.utc(lo);
        }
        b[i++] = OracleTime.utc(l.todayStart());
        for (long d : l.closedLo()) {
            b[i++] = OracleTime.day(d);
        }
        b[i++] = accountId;
        b[i++] = OracleTime.utc(l.oldestRolling());
        b[i++] = OracleTime.utc(l.now());
        b[i++] = accountId;
        b[i++] = OracleTime.day(l.oldestClosed());
        b[i] = OracleTime.day(l.today());
        return b;
    }

    private static String build() {
        List<String> lows = new ArrayList<>();
        WindowCatalog.ROLLING.stream().map(RollingWindow::suffix).forEach(lows::add);
        lows.add(TODAY);
        WindowCatalog.DAYS.stream().map(DayWindow::suffix).forEach(lows::add);
        StringBuilder sb = new StringBuilder("WITH b AS (SELECT ");
        for (int i = 0; i < lows.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append("? AS lo_").append(lows.get(i));
        }
        sb.append(" FROM dual) SELECT t.*, d.* FROM (SELECT ");
        for (RollingWindow w : WindowCatalog.ROLLING) {
            txAggregates(sb, w.suffix(), "x.ts > b.lo_" + w.suffix());
            sb.append(", ");
        }
        txAggregates(sb, TODAY, "x.ts >= b.lo_" + TODAY);
        sb.append(" FROM tx x CROSS JOIN b WHERE x.account_id = ? AND x.ts > ? AND x.ts <= ?) t CROSS JOIN (SELECT ");
        for (int i = 0; i < WindowCatalog.DAYS.size(); i++) {
            String s = WindowCatalog.DAYS.get(i).suffix();
            String cond = "a.dia >= b.lo_" + s;
            sb.append(i == 0 ? "" : ", ")
                    .append("SUM(CASE WHEN ").append(cond).append(" THEN a.cnt END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN a.sum_amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN a.min_amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN a.max_amount END) AS max_").append(s);
        }
        sb.append(" FROM daily_agg a CROSS JOIN b WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?) d");
        return sb.toString();
    }

    private static void txAggregates(StringBuilder sb, String s, String cond) {
        sb.append("COUNT(CASE WHEN ").append(cond).append(" THEN 1 END) AS cnt_").append(s)
                .append(", SUM(CASE WHEN ").append(cond).append(" THEN x.amount END) AS sum_").append(s)
                .append(", MIN(CASE WHEN ").append(cond).append(" THEN x.amount END) AS min_").append(s)
                .append(", MAX(CASE WHEN ").append(cond).append(" THEN x.amount END) AS max_").append(s);
    }
}
