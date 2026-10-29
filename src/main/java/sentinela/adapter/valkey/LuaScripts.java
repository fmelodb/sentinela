package sentinela.adapter.valkey;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Scripts Lua carregados com SCRIPT LOAD e chamados por EVALSHA; NOSCRIPT recarrega e repete uma vez. */
public final class LuaScripts {
    private final String ingestSource = resource("/lua/ingest.lua");
    private final String putDaySource = resource("/lua/put_day.lua");
    private volatile String ingestSha;
    private volatile String putDaySha;

    public void load(RedisCommands<String, String> sync) {
        ingestSha = sync.scriptLoad(ingestSource);
        putDaySha = sync.scriptLoad(putDaySource);
    }

    public void ingest(RedisCommands<String, String> sync, String key, long minute, long hour, long cents,
            long minMinute, long minHour) {
        String[] keys = {key};
        String[] args = {Long.toString(minute), Long.toString(hour), Long.toString(cents),
                Long.toString(minMinute), Long.toString(minHour)};
        try {
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        } catch (RedisNoScriptException e) {
            load(sync);
            sync.evalsha(ingestSha, ScriptOutputType.INTEGER, keys, args);
        }
    }

    /** Requer load() antes, numa conexão com auto-flush ligado. */
    public RedisFuture<Long> putDayAsync(RedisAsyncCommands<String, String> async, String key, long epochDay,
            String value, long minDay) {
        return async.evalsha(putDaySha, ScriptOutputType.INTEGER, new String[] {key},
                Long.toString(epochDay), value, Long.toString(minDay));
    }

    private static String resource(String path) {
        try (InputStream in = LuaScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("recurso ausente: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
