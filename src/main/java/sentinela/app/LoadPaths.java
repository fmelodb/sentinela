package sentinela.app;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Nomes de execução e de log, e o conteúdo do .meta.properties de Ingestor e Leitor. */
final class LoadPaths {
    private static final DateTimeFormatter RUN = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private LoadPaths() {}

    static String defaultRun(Instant start) {
        return RUN.format(start);
    }

    static Path defaultLog(String program, Mode mode, String run) {
        return Path.of("./logs", program + "-" + mode.cli() + "-" + run + ".csv");
    }

    static Map<String, String> meta(String program, Mode mode, String run, int tps, int duration, int warmup,
            int threads, long startMs) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("program", program);
        m.put("mode", mode.cli());
        m.put("run", run);
        m.put("tps", Integer.toString(tps));
        m.put("duration", Integer.toString(duration));
        m.put("warmup", Integer.toString(warmup));
        m.put("threads", Integer.toString(threads));
        m.put("start_epoch_ms", Long.toString(startMs));
        m.put("java.version", System.getProperty("java.version"));
        m.put("host", host() + " (" + Runtime.getRuntime().availableProcessors() + " CPUs, heap máx "
                + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB)");
        return m;
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "desconhecido";
        }
    }
}
