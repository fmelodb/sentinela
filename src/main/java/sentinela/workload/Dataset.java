package sentinela.workload;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import sentinela.infra.UsageException;

/** Arquivo NDJSON: {"tx_id":1,"account_id":4711,"amount":123.45} por linha. */
public final class Dataset {
    public record Row(long txId, long accountId, long amountCents) {}

    private static final JsonFactory JSON = new JsonFactory();

    private Dataset() {}

    public static void write(Path path, Iterator<Row> rows) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            while (rows.hasNext()) {
                Row r = rows.next();
                w.write("{\"tx_id\":");
                w.write(Long.toString(r.txId()));
                w.write(",\"account_id\":");
                w.write(Long.toString(r.accountId()));
                w.write(",\"amount\":");
                w.write(BigDecimal.valueOf(r.amountCents(), 2).toPlainString());
                w.write("}\n");
            }
        }
    }

    public static List<Row> readAll(Path path) throws IOException {
        List<Row> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = r.readLine()) != null) {
                lineNo++;
                if (!line.isBlank()) {
                    out.add(parse(line, lineNo));
                }
            }
        }
        return out;
    }

    private static Row parse(String line, int lineNo) {
        try (JsonParser p = JSON.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw bad(lineNo, "esperado um objeto JSON");
            }
            Long txId = null;
            Long accountId = null;
            Long amountCents = null;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                p.nextToken();
                switch (field) {
                    case "tx_id" -> txId = p.getLongValue();
                    case "account_id" -> accountId = p.getLongValue();
                    case "amount" -> amountCents = p.getDecimalValue().movePointRight(2).longValueExact();
                    default -> p.skipChildren();
                }
            }
            if (txId == null || accountId == null || amountCents == null) {
                throw bad(lineNo, "faltam campos (tx_id, account_id, amount)");
            }
            return new Row(txId, accountId, amountCents);
        } catch (IOException | ArithmeticException e) {
            throw bad(lineNo, e.getMessage());
        }
    }

    private static UsageException bad(int lineNo, String msg) {
        return new UsageException("dataset linha " + lineNo + ": " + msg);
    }
}
