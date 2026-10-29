package sentinela.infra;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parser de "--chave valor" e "--flag". Chaves aceitas são as dos defaults mais as flags. */
public final class Args {
    private final Map<String, String> values;
    private final Set<String> flags;

    private Args(Map<String, String> values, Set<String> flags) {
        this.values = values;
        this.flags = flags;
    }

    /** Monta o mapa de defaults a partir de pares chave, valor; valor null significa "sem default". */
    public static Map<String, String> defaults(String... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("pares chave/valor incompletos");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            m.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return m;
    }

    public static Args parse(String[] argv, Map<String, String> defaults, Set<String> flagNames) {
        Map<String, String> values = new HashMap<>(defaults);
        Set<String> flags = new HashSet<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) {
                throw new UsageException("argumento inesperado: " + a);
            }
            String key = a.substring(2);
            if (flagNames.contains(key)) {
                flags.add(key);
                continue;
            }
            if (!defaults.containsKey(key)) {
                throw new UsageException("argumento desconhecido: " + a);
            }
            if (i + 1 >= argv.length) {
                throw new UsageException("falta valor para " + a);
            }
            values.put(key, argv[++i]);
        }
        return new Args(values, flags);
    }

    public String string(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new UsageException("argumento obrigatório: --" + key);
        }
        return v;
    }

    public Optional<String> optional(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public int integer(String key) {
        String v = string(key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + key + " precisa ser inteiro: " + v);
        }
    }

    public int positiveInt(String key) {
        int v = integer(key);
        if (v <= 0) {
            throw new UsageException("--" + key + " precisa ser maior que zero: " + v);
        }
        return v;
    }

    public long longValue(String key) {
        String v = string(key);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new UsageException("--" + key + " precisa ser inteiro: " + v);
        }
    }

    public boolean flag(String key) {
        return flags.contains(key);
    }

    public Path path(String key) {
        return Path.of(string(key));
    }
}
