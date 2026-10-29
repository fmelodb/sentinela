package sentinela.infra;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Log de latência: os workers enfileiram, um thread dedicado escreve. Nenhum I/O no caminho quente. */
public final class CsvLog implements AutoCloseable {
    public static final String HEADER = "ts_epoch_ms,program,mode,operation,account_id,latency_us,status";

    private record Entry(long tsEpochMs, String operation, long accountId, long latencyUs, boolean ok) {}

    private static final Entry POISON = new Entry(0, null, 0, 0, false);

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(100_000);
    private final String program;
    private final String mode;
    private final Thread writer;
    private volatile IOException failure;

    public CsvLog(Path path, String program, String mode) throws IOException {
        this.program = program;
        this.mode = mode;
        Files.createDirectories(path.toAbsolutePath().getParent());
        BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        out.write(HEADER);
        out.write('\n');
        this.writer = Thread.ofPlatform().name("csv-log").start(() -> drain(out));
    }

    public void record(long tsEpochMs, String operation, long accountId, long latencyUs, boolean ok) {
        try {
            queue.put(new Entry(tsEpochMs, operation, accountId, latencyUs, ok));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido ao registrar no log", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            queue.put(POISON);
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrompido ao fechar o log", e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void drain(BufferedWriter out) {
        List<Entry> batch = new ArrayList<>(4096);
        long lastFlush = System.nanoTime();
        try {
            while (true) {
                Entry first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 4095);
                }
                for (Entry e : batch) {
                    if (e == POISON) {
                        finish(out);
                        return;
                    }
                    write(out, e);
                }
                batch.clear();
                if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                    flush(out);
                    lastFlush = System.nanoTime();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(out);
        }
    }

    private void write(BufferedWriter out, Entry e) {
        if (failure != null) {
            return; // continua esvaziando a fila para não travar os workers
        }
        try {
            out.write(Long.toString(e.tsEpochMs()));
            out.write(',');
            out.write(program);
            out.write(',');
            out.write(mode);
            out.write(',');
            out.write(e.operation());
            out.write(',');
            out.write(Long.toString(e.accountId()));
            out.write(',');
            out.write(Long.toString(e.latencyUs()));
            out.write(',');
            out.write(e.ok() ? "ok" : "error");
            out.write('\n');
        } catch (IOException ex) {
            failure = ex;
        }
    }

    private void flush(BufferedWriter out) {
        if (failure != null) {
            return;
        }
        try {
            out.flush();
        } catch (IOException ex) {
            failure = ex;
        }
    }

    private void finish(BufferedWriter out) {
        try {
            out.close();
        } catch (IOException ex) {
            if (failure == null) {
                failure = ex;
            }
        }
    }
}
