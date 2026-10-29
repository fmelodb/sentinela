package sentinela.report;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import sentinela.infra.CsvLog;
import sentinela.infra.RunMeta;

/** Lê os *.csv do diretório (sem recursão) que tenham .meta.properties ao lado. */
public final class LogLoader {
    private LogLoader() {}

    public static List<LogFile> load(Path dir, PrintStream warn) throws IOException {
        List<Path> csvs;
        try (Stream<Path> s = Files.list(dir)) {
            csvs = s.filter(p -> p.getFileName().toString().endsWith(".csv")).sorted().toList();
        }
        List<LogFile> out = new ArrayList<>();
        for (Path csv : csvs) {
            if (!Files.exists(RunMeta.sidecarOf(csv))) {
                warn.println("aviso: " + csv.getFileName() + " sem .meta.properties; ignorado");
                continue;
            }
            out.add(new LogFile(csv, RunMeta.read(csv), readRows(csv, warn)));
        }
        return out;
    }

    private static List<LogRow> readRows(Path csv, PrintStream warn) throws IOException {
        List<LogRow> rows = new ArrayList<>();
        int bad = 0;
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.equals(CsvLog.HEADER)) {
                    continue;
                }
                String[] f = line.split(",", -1);
                try {
                    if (f.length != 7) {
                        throw new NumberFormatException("colunas: " + f.length);
                    }
                    // intern(): programa, modo e operação se repetem em todas as linhas; sem isso,
                    // cada linha retém três Strings novas e vários runs estouram o heap do relatório.
                    rows.add(new LogRow(Long.parseLong(f[0]), f[1].intern(), f[2].intern(), f[3].intern(), Long.parseLong(f[4]),
                            Long.parseLong(f[5]), f[6].equals("ok")));
                } catch (NumberFormatException e) {
                    bad++;
                }
            }
        }
        if (bad > 0) {
            warn.println("aviso: " + csv.getFileName() + ": " + bad + " linha(s) malformada(s) ignorada(s)");
        }
        return rows;
    }
}
