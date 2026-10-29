package sentinela.adapter.valkey;

import io.lettuce.core.Range;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import sentinela.domain.DaySlice;
import sentinela.domain.FeatureStorePort;
import sentinela.domain.FeatureVector;
import sentinela.domain.Limits;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAggregator;
import sentinela.domain.WindowCatalog;
import sentinela.infra.StoreException;

/** Valkey como store: transações de 73h num sorted set por conta e dias fechados num hash (spec técnica 6). */
public final class ValkeyFeatureStore implements FeatureStorePort {
    /** As 72h da maior janela e 1h de folga para leituras concorrentes e relógios um pouco adiantados. */
    public static final Duration TX_RETENTION = Duration.ofHours(73);
    /** Dias fechados retidos: de T−91 (primeiro dia da 92d) a T−1. */
    public static final int DAY_SLICES = 91;

    private static final long TX_RETENTION_MICROS = TX_RETENTION.toNanos() / 1_000;
    private static final long READ_WAIT_SECONDS = 5;

    private final RedisCommands<String, String> sync;
    private final RedisAsyncCommands<String, String> async;
    private final LuaScripts scripts;
    private final ZoneId zone;

    public ValkeyFeatureStore(StatefulRedisConnection<String, String> conn, LuaScripts scripts, ZoneId zone) {
        this.sync = conn.sync();
        this.async = conn.async();
        this.scripts = scripts;
        this.zone = zone;
        scripts.load(sync);
    }

    @Override
    public void ingest(Transaction tx) {
        long ts = SliceCodec.micros(tx.ts());
        try {
            scripts.ingest(sync, SliceCodec.txKey(tx.accountId()), ts, SliceCodec.member(tx), ts - TX_RETENTION_MICROS);
        } catch (RedisException e) {
            throw new StoreException("ingest no Valkey, tx_id " + tx.txId(), e);
        }
    }

    @Override
    public FeatureVector read(long accountId, Instant now) {
        Limits l = WindowCatalog.limits(now, zone);
        List<ScoredValue<String>> recent;
        Map<String, String> days;
        try {
            // Os dois comandos saem juntos pela conexão multiplexada: uma ida ao servidor.
            RedisFuture<List<ScoredValue<String>>> fr = async.zrangebyscoreWithScores(SliceCodec.txKey(accountId),
                    Range.from(Range.Boundary.excluding(SliceCodec.micros(l.oldestRolling())),
                            Range.Boundary.including(SliceCodec.micros(l.now()))));
            RedisFuture<Map<String, String>> fd = async.hgetall(SliceCodec.key(accountId));
            recent = fr.get(READ_WAIT_SECONDS, TimeUnit.SECONDS);
            days = fd.get(READ_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (RedisException | ExecutionException | TimeoutException e) {
            throw new StoreException("read no Valkey, conta " + accountId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("read no Valkey interrompido, conta " + accountId, e);
        }
        List<Transaction> txs = new ArrayList<>(recent.size());
        for (ScoredValue<String> sv : recent) {
            txs.add(SliceCodec.decodeMember(accountId, sv.getValue(), sv.getScore()));
        }
        List<DaySlice> slices = new ArrayList<>(days.size());
        days.forEach((field, value) -> slices.add(SliceCodec.decodeDay(field, value)));
        return WindowAggregator.aggregate(txs, slices, l);
    }
}
