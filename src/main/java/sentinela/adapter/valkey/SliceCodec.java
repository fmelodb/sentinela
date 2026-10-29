package sentinela.adapter.valkey;

import sentinela.domain.Slice;
import sentinela.domain.Unit;
import sentinela.domain.WindowAgg;

/** Formato do hash: chave fs:{conta}, campo m:/h:/d:<índice epoch>, valor cnt|sum|min|max em centavos. */
public final class SliceCodec {
    private SliceCodec() {}

    public static String key(long accountId) {
        return "fs:{" + accountId + "}";
    }

    public static String field(Unit unit, long index) {
        return switch (unit) {
            case MINUTE -> "m:";
            case HOUR -> "h:";
            case DAY -> "d:";
        } + index;
    }

    public static String encode(WindowAgg a) {
        if (a.cnt() == 0) {
            throw new IllegalArgumentException("fatia vazia não é gravada");
        }
        return a.cnt() + "|" + a.sumCents() + "|" + a.minCents() + "|" + a.maxCents();
    }

    public static Slice decode(String field, String value) {
        if (field.length() < 3 || field.charAt(1) != ':') {
            throw new IllegalArgumentException("campo inválido: " + field);
        }
        Unit unit = switch (field.charAt(0)) {
            case 'm' -> Unit.MINUTE;
            case 'h' -> Unit.HOUR;
            case 'd' -> Unit.DAY;
            default -> throw new IllegalArgumentException("campo inválido: " + field);
        };
        long index = Long.parseLong(field, 2, field.length(), 10);
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
        return new Slice(unit, index, new WindowAgg(v[0], v[1], v[2], v[3]));
    }
}
