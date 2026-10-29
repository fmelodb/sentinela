package sentinela.adapter.oracle;

import java.util.ArrayList;
import java.util.List;

/**
 * Plano esperado da leitura (spec técnica 5.3): poda de partições sobre o índice local coberto,
 * PK da IOT para DAILY_AGG e nenhum acesso à tabela. Se o plano for outro, a rodada não vale.
 */
public final class PlanCheck {
    private PlanCheck() {}

    public static List<String> problems(List<String> plan) {
        String text = String.join("\n", plan);
        List<String> out = new ArrayList<>();
        if (!text.contains("PARTITION RANGE ITERATOR")) {
            out.add("sem PARTITION RANGE ITERATOR: a leitura não poda as partições de TX");
        }
        if (!text.contains("TX_ACC_TS_AMT_IX")) {
            out.add("índice TX_ACC_TS_AMT_IX não usado");
        }
        if (!text.contains("DAILY_AGG_PK")) {
            out.add("DAILY_AGG_PK não usado");
        }
        if (text.contains("TABLE ACCESS")) {
            out.add("TABLE ACCESS no plano: o índice coberto não foi suficiente");
        }
        return out;
    }
}
