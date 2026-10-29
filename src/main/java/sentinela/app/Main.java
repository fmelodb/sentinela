package sentinela.app;

import java.util.Arrays;
import sentinela.infra.ExitCodes;
import sentinela.infra.StoreUnavailableException;
import sentinela.infra.UsageException;

public final class Main {
    private static final String USAGE =
            "uso: java -jar sentinela.jar <gerador|ingestor|leitor|backfill|job-diario|relatorio|equivalencia|schema>"
                    + " [--arg valor ...]";

    private Main() {}

    public static void main(String[] argv) {
        System.exit(run(argv));
    }

    static int run(String[] argv) {
        if (argv.length == 0) {
            return usage();
        }
        String[] rest = Arrays.copyOfRange(argv, 1, argv.length);
        try {
            return switch (argv[0]) {
                case "gerador" -> GeneratorApp.run(rest);
                case "relatorio" -> ReportApp.run(rest);
                case "schema" -> SchemaApp.run(rest);
                default -> usage();
            };
        } catch (UsageException e) {
            System.err.println("erro: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (StoreUnavailableException e) {
            System.err.println("store indisponível: " + e.getMessage());
            e.printStackTrace();
            return ExitCodes.STORE_UNAVAILABLE;
        } catch (Exception e) {
            System.err.println("erro inesperado: " + e);
            e.printStackTrace();
            return ExitCodes.UNEXPECTED;
        }
    }

    private static int usage() {
        System.err.println(USAGE);
        return ExitCodes.USAGE;
    }
}
