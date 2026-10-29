package sentinela.infra;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Arquivo .meta.properties ao lado de cada log: o Relatório recebe só o diretório. */
public final class RunMeta {
    private RunMeta() {}

    public static Path sidecarOf(Path csv) {
        String name = csv.getFileName().toString();
        String base = name.endsWith(".csv") ? name.substring(0, name.length() - 4) : name;
        return csv.resolveSibling(base + ".meta.properties");
    }

    public static void write(Path csv, Map<String, String> values) throws IOException {
        Properties p = new Properties();
        p.putAll(values);
        store(csv, p);
    }

    public static void put(Path csv, String key, String value) throws IOException {
        Properties p = read(csv);
        p.setProperty(key, value);
        store(csv, p);
    }

    public static Properties read(Path csv) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(sidecarOf(csv), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static void store(Path csv, Properties p) throws IOException {
        try (Writer w = Files.newBufferedWriter(sidecarOf(csv), StandardCharsets.UTF_8)) {
            p.store(w, "sentinela");
        }
    }
}
