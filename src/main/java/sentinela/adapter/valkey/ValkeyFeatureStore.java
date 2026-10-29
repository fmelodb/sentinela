package sentinela.adapter.valkey;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Slice;
import sentinela.domain.SliceAggregator;
import sentinela.domain.Transaction;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Valkey como store: um hash por conta com fatias de minuto, hora e dia. */
public final class ValkeyFeatureStore implements FeatureStorePort {
    public static final int MINUTE_SLICES = 60;
    public static final int HOUR_SLICES = 72;
    public static final int DAY_SLICES = 92;

    private final RedisCommands<String, String> sync;
    private final LuaScripts scripts;
    private final ZoneId zone;

    public ValkeyFeatureStore(StatefulRedisConnection<String, String> conn, LuaScripts scripts, ZoneId zone) {
        this.sync = conn.sync();
        this.scripts = scripts;
        this.zone = zone;
        scripts.load(sync);
    }

    @Override
    public void ingest(Transaction tx) {
        long minute = WindowCatalog.epochMinute(tx.ts());
        long hour = WindowCatalog.epochHour(tx.ts());
        try {
            scripts.ingest(sync, SliceCodec.key(tx.accountId()), minute, hour, tx.amountCents(),
                    minute - (MINUTE_SLICES - 1), hour - (HOUR_SLICES - 1));
        } catch (RedisException e) {
            throw new StoreException("ingest no Valkey, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Map<String, String> hash;
        try {
            hash = sync.hgetall(SliceCodec.key(accountId));
        } catch (RedisException e) {
            throw new StoreException("read no Valkey, conta " + accountId, e);
        }
        List<Slice> slices = new ArrayList<>(hash.size());
        hash.forEach((field, value) -> slices.add(SliceCodec.decode(field, value)));
        return SliceAggregator.aggregate(slices, WindowCatalog.bounds(now, zone));
    }
}
