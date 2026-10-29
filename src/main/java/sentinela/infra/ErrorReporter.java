package sentinela.infra;

import java.io.PrintStream;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Os 10 primeiros erros com stack trace; depois, resumo por tipo a cada 10 s e no fechamento. */
public final class ErrorReporter implements AutoCloseable {
    private static final int FULL_TRACES = 10;

    private final PrintStream err;
    private final AtomicInteger total = new AtomicInteger();
    private final Map<String, LongAdder> byType = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer;

    public ErrorReporter(PrintStream err) {
        this.err = err;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "erros");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::summary, 10, 10, TimeUnit.SECONDS);
    }

    public void report(Throwable t) {
        int n = total.incrementAndGet();
        byType.computeIfAbsent(key(t), k -> new LongAdder()).increment();
        if (n <= FULL_TRACES) {
            synchronized (err) {
                err.println("erro #" + n + ":");
                t.printStackTrace(err);
            }
        }
    }

    public int total() {
        return total.get();
    }

    @Override
    public void close() {
        timer.shutdownNow();
        if (total.get() > 0) {
            err.println("total de erros: " + total.get() + " " + snapshot());
        }
    }

    private void summary() {
        if (total.get() > FULL_TRACES) {
            err.println("erros até agora: " + total.get() + " " + snapshot());
        }
    }

    private Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        byType.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    private static String key(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String k = t.getClass().getSimpleName();
        return root == t ? k : k + " <- " + root.getClass().getSimpleName();
    }
}
