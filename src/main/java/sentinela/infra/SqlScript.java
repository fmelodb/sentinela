package sentinela.infra;

import java.util.ArrayList;
import java.util.List;

/** Divide um script SQL simples (sem blocos PL/SQL) em comandos para JDBC. */
public final class SqlScript {
    private SqlScript() {}

    public static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || (trimmed.isEmpty() && current.isEmpty())) {
                continue;
            }
            if (trimmed.endsWith(";")) {
                String last = line.substring(0, line.lastIndexOf(';'));
                current.append(last);
                String stmt = current.toString().strip();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        String rest = current.toString().strip();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }
}
