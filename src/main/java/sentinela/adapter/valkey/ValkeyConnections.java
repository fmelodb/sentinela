package sentinela.adapter.valkey;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import sentinela.infra.Env;
import sentinela.infra.StoreUnavailableException;

/** Um cliente Lettuce; uma conexão compartilhada (multiplexada) para o caminho quente. */
public final class ValkeyConnections implements AutoCloseable {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> hot;

    private ValkeyConnections(RedisClient client, StatefulRedisConnection<String, String> hot) {
        this.client = client;
        this.hot = hot;
    }

    public static ValkeyConnections open(Env env) {
        env.requireValkey();
        RedisURI uri = RedisURI.create(env.valkeyUrl());
        uri.setTimeout(COMMAND_TIMEOUT);
        RedisClient client = RedisClient.create(uri);
        try {
            StatefulRedisConnection<String, String> hot = client.connect();
            hot.sync().ping();
            return new ValkeyConnections(client, hot);
        } catch (RedisException e) {
            client.shutdown();
            throw new StoreUnavailableException("Valkey em " + env.valkeyUrl(), e);
        }
    }

    public StatefulRedisConnection<String, String> hot() {
        return hot;
    }

    /** Conexão dedicada para carga em massa; quem abre fecha. */
    public StatefulRedisConnection<String, String> openBulk() {
        return client.connect();
    }

    @Override
    public void close() {
        hot.close();
        client.shutdown();
    }
}
