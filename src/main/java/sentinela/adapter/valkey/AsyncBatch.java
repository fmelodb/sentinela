package sentinela.adapter.valkey;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import sentinela.infra.StoreException;

/** Pipeline manual: auto-flush desligado, flush e espera a cada N comandos. Só na conexão de carga em massa. */
final class AsyncBatch implements AutoCloseable {
    private static final Duration WAIT = Duration.ofSeconds(60);

    private final StatefulRedisConnection<String, String> conn;
    private final RedisAsyncCommands<String, String> async;
    private final int size;
    private final List<RedisFuture<?>> pending = new ArrayList<>();
    private long completed;
    private boolean closed;

    AsyncBatch(StatefulRedisConnection<String, String> conn, int size) {
        this.conn = conn;
        this.size = size;
        conn.setAutoFlushCommands(false);
        this.async = conn.async();
    }

    RedisAsyncCommands<String, String> commands() {
        return async;
    }

    void add(RedisFuture<?> future) {
        pending.add(future);
        if (pending.size() >= size) {
            flush();
        }
    }

    long completed() {
        return completed;
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        conn.flushCommands();
        if (!LettuceFutures.awaitAll(WAIT, pending.toArray(new RedisFuture<?>[0]))) {
            throw new StoreException("timeout de " + WAIT + " num lote de " + pending.size() + " comandos", null);
        }
        completed += pending.size();
        pending.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
        } finally {
            conn.setAutoFlushCommands(true);
        }
    }
}
