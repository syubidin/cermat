package id.cermat.rule;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Satu jenis aturan deteksi. LOGIKA ada di kode (Java), tetapi PARAMETER dan status aktif
 * ada di tabel fraud_rules, sehingga ambang batas bisa diubah operator tanpa deploy ulang.
 */
public interface Rule {

    /** Harus sama dengan kolom code di tabel fraud_rules. */
    String code();

    /** Mengembalikan alasan jika transaksi mencurigakan, atau kosong jika wajar. */
    Optional<String> evaluate(RuleContext ctx, JsonNode params);

    /** Dipanggil saat operator mengubah parameter. Lempar IllegalArgumentException jika tidak valid. */
    void validateParams(JsonNode params);
}
