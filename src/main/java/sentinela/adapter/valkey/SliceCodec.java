package sentinela.adapter.valkey;

import java.time.Instant;
import sentinela.domain.DaySlice;
import sentinela.domain.Transaction;
import sentinela.domain.WindowAgg;

/**
 * Formato no Valkey (spec técnica 6.1): sorted set fs:{conta}:tx com score = ts em µs e membro
 * tx_id|centavos; hash fs:{conta} com campos d:<epoch dia> e valor cnt|sum|min|max em centavos.
 */
public final class SliceCodec {
    private SliceCodec() {}

    public static String key(long accountId) {
        return "fs:{" + accountId + "}";
    }

    public static String txKey(long accountId) {
        return key(accountId) + ":tx";
    }

    public static String dayField(long epochDay) {
        return "d:" + epochDay;
    }

    public static long micros(Instant t) {
        return Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000);
    }

    public static Instant instant(long micros) {
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
    }

    public static String member(Transaction tx) {
        return tx.txId() + "|" + tx.amountCents();
    }

    public static Transaction decodeMember(long accountId, String member, double score) {
        int bar = member.indexOf('|');
        if (bar <= 0 || bar != member.lastIndexOf('|')) {
            throw new IllegalArgumentException("membro inválido: " + member);
        }
        long micros = (long) score;
        if (micros != score) {
            throw new IllegalArgumentException("score não é um inteiro de microssegundos: " + score);
        }
        try {
            return new Transaction(Long.parseLong(member, 0, bar, 10), accountId, instant(micros),
                    Long.parseLong(member, bar + 1, member.length(), 10));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("membro inválido: " + member, e);
        }
    }

    public static String encode(WindowAgg a) {
        if (a.cnt() == 0) {
            throw new IllegalArgumentException("fatia vazia não é gravada");
        }
        return a.cnt() + "|" + a.sumCents() + "|" + a.minCents() + "|" + a.maxCents();
    }

    public static DaySlice decodeDay(String field, String value) {
        if (field.length() < 3 || !field.startsWith("d:")) {
            throw new IllegalArgumentException("campo inválido (só há fatias d: no hash): " + field);
        }
        long epochDay = Long.parseLong(field, 2, field.length(), 10);
        long[] v = new long[4];
        int k = 0;
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            if (i == value.length() || value.charAt(i) == '|') {
                if (k == 4) {
                    throw new IllegalArgumentException("valor inválido: " + value);
                }
                v[k++] = Long.parseLong(value, start, i, 10);
                start = i + 1;
            }
        }
        if (k != 4) {
            throw new IllegalArgumentException("valor inválido: " + value);
        }
        return new DaySlice(epochDay, new WindowAgg(v[0], v[1], v[2], v[3]));
    }
}
