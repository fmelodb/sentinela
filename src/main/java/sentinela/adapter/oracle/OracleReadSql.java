package sentinela.adapter.oracle;

import static sentinela.domain.Unit.DAY;
import static sentinela.domain.Unit.HOUR;
import static sentinela.domain.Unit.MINUTE;

import java.util.List;
import sentinela.domain.Bounds;
import sentinela.domain.Window;
import sentinela.domain.WindowCatalog;

/**
 * Gera, a partir do WindowCatalog, o statement único da leitura: agregados condicionais sobre TX
 * (15 janelas de minuto e hora) em CROSS JOIN com agregados sobre DAILY_AGG (4 janelas de dia).
 * Os limites de cada janela entram como colunas da linha b; os predicados de poda usam binds diretos.
 */
final class OracleReadSql {
    static final String SQL = build();

    private static final int LAST_HOUR_WINDOW = WindowCatalog.FIRST_DAY_WINDOW - 1;
    private static final int LAST_DAY_WINDOW = WindowCatalog.SIZE - 1;

    private OracleReadSql() {}

    static Object[] binds(long accountId, List<Bounds> bounds) {
        Object[] b = new Object[WindowCatalog.SIZE + 6];
        int i = 0;
        for (Bounds x : bounds) {
            b[i++] = x.unit() == DAY ? OracleTime.day(x.lo()) : OracleTime.sliceStart(x.unit(), x.lo());
        }
        Bounds currentMinute = bounds.get(0);
        Bounds hours72 = bounds.get(LAST_HOUR_WINDOW);
        Bounds days92 = bounds.get(LAST_DAY_WINDOW);
        b[i++] = accountId;
        b[i++] = OracleTime.sliceStart(HOUR, hours72.lo());
        b[i++] = OracleTime.sliceStart(MINUTE, currentMinute.hi() + 1);
        b[i++] = accountId;
        b[i++] = OracleTime.day(days92.lo());
        b[i] = OracleTime.day(days92.hi() + 1);
        return b;
    }

    private static String build() {
        List<Window> ws = WindowCatalog.WINDOWS;
        StringBuilder sb = new StringBuilder("WITH b AS (SELECT ");
        for (int i = 0; i < ws.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append("? AS lo_").append(ws.get(i).suffix());
        }
        sb.append(" FROM dual) SELECT t.*, d.* FROM (SELECT ");
        for (int i = 0; i < WindowCatalog.FIRST_DAY_WINDOW; i++) {
            String s = ws.get(i).suffix();
            String cond = "x.ts >= b.lo_" + s;
            sb.append(i == 0 ? "" : ", ")
                    .append("COUNT(CASE WHEN ").append(cond).append(" THEN 1 END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN x.amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN x.amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN x.amount END) AS max_").append(s);
        }
        sb.append(" FROM tx x CROSS JOIN b WHERE x.account_id = ? AND x.ts >= ? AND x.ts < ?) t CROSS JOIN (SELECT ");
        for (int i = WindowCatalog.FIRST_DAY_WINDOW; i < ws.size(); i++) {
            String s = ws.get(i).suffix();
            String cond = "a.dia >= b.lo_" + s;
            sb.append(i == WindowCatalog.FIRST_DAY_WINDOW ? "" : ", ")
                    .append("SUM(CASE WHEN ").append(cond).append(" THEN a.cnt END) AS cnt_").append(s)
                    .append(", SUM(CASE WHEN ").append(cond).append(" THEN a.sum_amount END) AS sum_").append(s)
                    .append(", MIN(CASE WHEN ").append(cond).append(" THEN a.min_amount END) AS min_").append(s)
                    .append(", MAX(CASE WHEN ").append(cond).append(" THEN a.max_amount END) AS max_").append(s);
        }
        sb.append(" FROM daily_agg a CROSS JOIN b WHERE a.account_id = ? AND a.dia >= ? AND a.dia < ?) d");
        return sb.toString();
    }
}
