package sentinela.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import sentinela.infra.ExitCodes;
import sentinela.infra.UsageException;
import sentinela.report.GroupStats;
import sentinela.report.LogFile;
import sentinela.report.LogLoader;
import sentinela.report.ReportBuilder;
import sentinela.report.ReportWriter;

/** relatorio <diretório de logs>: grava report.md, report.csv e tps-series.csv em <dir>/report/. */
public final class ReportApp {
    private ReportApp() {}

    public static int run(String[] argv) throws Exception {
        if (argv.length != 1) {
            throw new UsageException("uso: relatorio <diretório de logs>");
        }
        Path dir = Path.of(argv[0]);
        if (!Files.isDirectory(dir)) {
            throw new UsageException("diretório não encontrado: " + dir);
        }
        List<LogFile> files = LogLoader.load(dir, System.err);
        List<GroupStats> stats = ReportBuilder.build(files, System.err);
        Path out = dir.resolve("report");
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.md"), ReportWriter.markdown(stats), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("report.csv"), ReportWriter.csv(stats), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("tps-series.csv"), ReportWriter.seriesCsv(ReportBuilder.series(files)),
                StandardCharsets.UTF_8);
        System.err.printf("relatorio: %d arquivo(s), %d grupo(s) -> %s%n", files.size(), stats.size(), out);
        return ExitCodes.OK;
    }
}
