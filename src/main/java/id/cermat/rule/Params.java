package id.cermat.rule;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;

/** Pembaca parameter JSON yang tegas: nilai salah ditolak dengan pesan jelas, bukan diam-diam diabaikan. */
final class Params {
    private Params() {}

    static BigDecimal positiveDecimal(JsonNode params, String field) {
        JsonNode n = field(params, field);
        if (!n.isNumber() || n.decimalValue().signum() <= 0) {
            throw new IllegalArgumentException("params." + field + " harus berupa angka positif");
        }
        return n.decimalValue();
    }

    static int positiveInt(JsonNode params, String field) {
        JsonNode n = field(params, field);
        if (!n.isIntegralNumber() || n.asLong() <= 0 || n.asLong() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("params." + field + " harus berupa bilangan bulat positif");
        }
        return n.asInt();
    }

    private static JsonNode field(JsonNode params, String field) {
        if (params == null || !params.isObject()) {
            throw new IllegalArgumentException("params harus berupa objek JSON");
        }
        JsonNode n = params.get(field);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("params." + field + " wajib diisi");
        }
        return n;
    }
}
