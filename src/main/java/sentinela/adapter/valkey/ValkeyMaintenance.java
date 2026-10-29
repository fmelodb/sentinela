package sentinela.adapter.valkey;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import sentinela.domain.AccountAgg;
import sentinela.domain.KeyedSlice;
import sentinela.domain.Transaction;

/** Operações fora do port: reset, fatias de dia do Job e reconstrução a partir do Oracle (sorted set e hash). */
public final class ValkeyMaintenance {
    private static final int BATCH = 1_000;

    private final StatefulRedisConnection<String, String> bulk;
    private final RedisCommands<String, String> sync;
    private final LuaScripts scripts;

    public ValkeyMaintenance(StatefulRedisConnection<String, String> bulk, LuaScripts scripts) {
        this.bulk = bulk;
        this.sync = bulk.sync();
        this.scripts = scripts;
    }

    public void reset() {
        sync.flushdb();
    }

    public boolean isEmpty() {
        return sync.dbsize() == 0;
    }

    public int putDays(long epochDay, List<AccountAgg> rows, long todayEpochDay) {
        long minDay = todayEpochDay - ValkeyFeatureStore.DAY_SLICES;
        if (epochDay < minDay) {
            return 0;
        }
        scripts.load(sync); // antes de desligar o auto-flush
        try (AsyncBatch batch = new AsyncBatch(bulk, BATCH)) {
            for (AccountAgg r : rows) {
                batch.add(scripts.putDayAsync(batch.commands(), SliceCodec.key(r.accountId()), epochDay,
                        SliceCodec.encode(r.agg()), minDay));
            }
        }
        return rows.size();
    }

    public SliceWriter sliceWriter() {
        return new SliceWriter(new AsyncBatch(bulk, BATCH));
    }

    /** Grava transações (ZADD) e fatias de dia (HSET) sem Lua: usado logo depois de um reset. */
    public static final class SliceWriter implements AutoCloseable {
        private final AsyncBatch batch;

        private SliceWriter(AsyncBatch batch) {
            this.batch = batch;
        }

        public void writeTx(Transaction tx) {
            batch.add(batch.commands().zadd(SliceCodec.txKey(tx.accountId()), (double) SliceCodec.micros(tx.ts()),
                    SliceCodec.member(tx)));
        }

        public void writeDay(KeyedSlice s) {
            batch.add(batch.commands().hset(SliceCodec.key(s.accountId()), SliceCodec.dayField(s.slice().epochDay()),
                    SliceCodec.encode(s.slice().agg())));
        }

        public long written() {
            return batch.completed();
        }

        @Override
        public void close() {
            batch.close();
        }
    }
}
