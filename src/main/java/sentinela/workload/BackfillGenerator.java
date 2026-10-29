package sentinela.workload;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import sentinela.domain.AccountAgg;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;
import sentinela.domain.WindowCatalog;
import sentinela.workload.AccountSampler.Distribution;

/**
 * Histórico do Backfill: transações de 00:00 de D-3 até agora (vão para TX) e agregados diários
 * sintéticos para dias mais antigos (vão direto para DAILY_AGG).
 */
public final class BackfillGenerator {
    public static final long TX_ID_BASE = 1_000_000_000_000_000L;
    public static final int RECENT_DAYS = 3;

    private final int accounts;
    private final Distribution distribution;
    private final int txPerDay;
    private final long seed;
    private final ZoneId zone;
    private final Instant now;
    private final long today;

    public BackfillGenerator(int accounts, Distribution distribution, int txPerDay, long seed, ZoneId zone, Instant now) {
        this.accounts = accounts;
        this.distribution = distribution;
        this.txPerDay = txPerDay;
        this.seed = seed;
        this.zone = zone;
        this.now = now;
        this.today = WindowCatalog.epochDay(now, zone);
    }

    public long today() {
        return today;
    }

    public Iterator<Transaction> recentTransactions() {
        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, Seeds.stream(seed, "backfill.contas"));
        AmountSampler amounts = new AmountSampler(Seeds.stream(seed, "backfill.valores"));
        SplittableRandom tsRnd = Seeds.stream(seed, "backfill.ts");
        return new Iterator<>() {
            private long day = today - RECENT_DAYS;
            private long remaining = -1;
            private Instant dayStart;
            private long spanMicros;
            private long nextId = TX_ID_BASE + 1;

            @Override
            public boolean hasNext() {
                while (remaining <= 0) {
                    if (remaining == 0) {
                        day++;
                    }
                    if (day > today) {
                        return false;
                    }
                    dayStart = WindowCatalog.dayStart(day, zone);
                    Instant nextDay = WindowCatalog.dayStart(day + 1, zone);
                    Instant end = nextDay.isBefore(now) ? nextDay : now;
                    spanMicros = Duration.between(dayStart, end).toNanos() / 1_000;
                    long dayMicros = Duration.between(dayStart, nextDay).toNanos() / 1_000;
                    remaining = spanMicros <= 0 ? 0
                            : Math.round((double) accounts * txPerDay * spanMicros / dayMicros);
                    if (remaining == 0 && day == today) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public Transaction next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                remaining--;
                Instant ts = dayStart.plus(tsRnd.nextLong(spanMicros), ChronoUnit.MICROS);
                return new Transaction(nextId++, accountsSampler.next(), ts, amounts.nextCents());
            }
        };
    }

    public List<AccountAgg> syntheticDay(long epochDay) {
        SplittableRandom rnd = Seeds.stream(seed, "backfill.dia." + epochDay);
        AccountSampler accountsSampler = new AccountSampler(accounts, distribution, rnd);
        AmountSampler amounts = new AmountSampler(rnd);
        long[] cnt = new long[accounts + 1];
        long[] sum = new long[accounts + 1];
        long[] min = new long[accounts + 1];
        long[] max = new long[accounts + 1];
        long total = (long) accounts * txPerDay;
        for (long i = 0; i < total; i++) {
            int acc = (int) accountsSampler.next();
            long v = amounts.nextCents();
            if (cnt[acc] == 0) {
                min[acc] = v;
                max[acc] = v;
            } else {
                min[acc] = Math.min(min[acc], v);
                max[acc] = Math.max(max[acc], v);
            }
            cnt[acc]++;
            sum[acc] += v;
        }
        List<AccountAgg> out = new ArrayList<>();
        for (int acc = 1; acc <= accounts; acc++) {
            if (cnt[acc] > 0) {
                out.add(new AccountAgg(acc, new WindowAgg(cnt[acc], sum[acc], min[acc], max[acc])));
            }
        }
        return out;
    }
}
