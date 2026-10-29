package sentinela.workload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;
import sentinela.infra.CsvLog;
import sentinela.infra.ErrorReporter;

/**
 * Carga em laço aberto: um despachante emite um item a cada 1/tps s; workers executam os passos
 * em sequência e registram uma linha de log por passo. A latência cobre só a chamada do passo.
 */
public final class LoadRunner<T> {
    public record Step<T>(String operation, Consumer<T> call, ToLongFunction<T> accountOf) {}

    public record Result(long dispatched, boolean sourceExhausted) {}

    private static final Object POISON = new Object();

    private final Iterator<T> source;
    private final List<Step<T>> steps;
    private final int tps;
    private final Duration duration;
    private final int threads;
    private final CsvLog log;
    private final ErrorReporter errors;

    public LoadRunner(Iterator<T> source, List<Step<T>> steps, int tps, Duration duration, int threads,
            CsvLog log, ErrorReporter errors) {
        this.source = source;
        this.steps = List.copyOf(steps);
        this.tps = tps;
        this.duration = duration;
        this.threads = threads;
        this.log = log;
        this.errors = errors;
    }

    public Result run() throws InterruptedException {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(Math.max(1, tps));
        List<Thread> workers = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            workers.add(Thread.ofPlatform().name("worker-" + i).start(() -> work(queue)));
        }
        long start = System.nanoTime();
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
            queue.put(source.next());
            k++;
        }
        for (int i = 0; i < threads; i++) {
            queue.put(POISON);
        }
        for (Thread w : workers) {
            w.join();
        }
        return new Result(k, exhausted);
    }

    @SuppressWarnings("unchecked")
    private void work(BlockingQueue<Object> queue) {
        try {
            while (true) {
                Object o = queue.take();
                if (o == POISON) {
                    return;
                }
                T item = (T) o;
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
