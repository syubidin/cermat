package id.cermat.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Data transaksi yang sedang dievaluasi (belum tersimpan, jadi riwayat tidak menghitung dirinya sendiri). */
public record RuleContext(UUID fromAccountId, UUID toAccountId, BigDecimal amount, Instant now) {}
