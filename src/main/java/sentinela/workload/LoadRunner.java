package sentinela.workload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import sentinela.infra.CsvLog;
import sentinela.infra.ErrorReporter;

/**
 * Carga em laço aberto: um despachante emite um item a cada 1/tps s; workers executam os passos
 * em sequência e registram uma linha de log por passo. A latência cobre só a chamada do passo.
 * O atraso entre o instante agendado e o início da execução (fila) é medido à parte, a partir do
 * fim do warmup: é ele que revela saturação que o TPS e os percentis escondem.
 */
public final class LoadRunner<T> {
    public static final long LATE_THRESHOLD_MS = 100;

    public record Step<T>(String operation, Consumer<T> call, ToLongFunction<T> accountOf) {}

    /** measuredStarts/lateStarts/maxStartLagMs contam só itens que começaram depois do warmup. */
    public record Result(long dispatched, boolean sourceExhausted, long measuredStarts, long lateStarts,
            long maxStartLagMs) {}

    private record Scheduled(Object item, long dueNanos) {}

    private static final Scheduled POISON = new Scheduled(null, 0);

    private final Iterator<T> source;
    private final List<Step<T>> steps;
    private final int tps;
    private final Duration duration;
    private final Duration warmup;
    private final int threads;
    private final CsvLog log;
    private final ErrorReporter errors;
    private final LongAdder measured = new LongAdder();
    private final LongAdder late = new LongAdder();
    private final LongAccumulator maxLagMs = new LongAccumulator(Math::max, 0);
    private volatile long measureFromNanos = Long.MAX_VALUE;

    public LoadRunner(Iterator<T> source, List<Step<T>> steps, int tps, Duration duration, Duration warmup,
            int threads, CsvLog log, ErrorReporter errors) {
        this.source = source;
        this.steps = List.copyOf(steps);
        this.tps = tps;
        this.duration = duration;
        this.warmup = warmup;
        this.threads = threads;
        this.log = log;
        this.errors = errors;
    }

    public Result run() throws InterruptedException {
        BlockingQueue<Scheduled> queue = new ArrayBlockingQueue<>(Math.max(1, tps));
        List<Thread> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().name("worker-" + i).start(() -> work(queue)));
        }
        long start = System.nanoTime();
        measureFromNanos = start + warmup.toNanos();
        long end = start + duration.toNanos();
        long k = 0;
        boolean exhausted = false;
        while (true) {
            long due = start + (k * 1_000_000_000L) / tps;
            if (due >= end) {
                break;
            }
            if (!source.hasNext()) {
                exhausted = true;
                break;
            }
            long wait = due - System.nanoTime();
            while (wait > 0) {
                LockSupport.parkNanos(wait);
                wait = due - System.nanoTime();
            }
            queue.put(new Scheduled(source.next(), due));
            k++;
        }
        for (int i = 0; i < threads; i++) {
            queue.put(POISON);
        }
        for (Thread w : workers) {
            w.join();
        }
        return new Result(k, exhausted, measured.sum(), late.sum(), maxLagMs.get());
    }

    @SuppressWarnings("unchecked")
    private void work(BlockingQueue<Scheduled> queue) {
        try {
            while (true) {
                Scheduled s = queue.take();
                if (s == POISON) {
                    return;
                }
                long startNs = System.nanoTime();
                if (startNs >= measureFromNanos) {
                    long lagMs = (startNs - s.dueNanos()) / 1_000_000;
                    measured.increment();
                    maxLagMs.accumulate(lagMs);
                    if (lagMs > LATE_THRESHOLD_MS) {
                        late.increment();
                    }
                }
                T item = (T) s.item();
                for (Step<T> step : steps) {
                    long tsMs = System.currentTimeMillis();
                    long t0 = System.nanoTime();
                    boolean ok = true;
                    try {
                        step.call().accept(item);
                    } catch (RuntimeException e) {
                        ok = false;
                        errors.report(e);
                    }
                    long latencyUs = (System.nanoTime() - t0) / 1_000;
                    log.record(tsMs, step.operation(), step.accountOf().applyAsLong(item), latencyUs, ok);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
