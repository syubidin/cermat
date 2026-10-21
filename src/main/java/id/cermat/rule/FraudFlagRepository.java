package id.cermat.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FraudFlagRepository {

    public record FlagView(long id, UUID transactionId, String referenceNo, String ruleCode, String reason,
                           String reviewStatus, UUID reviewedBy, Instant reviewedAt, String reviewNote,
                           Instant createdAt, BigDecimal amount, UUID fromAccountId, UUID toAccountId) {}

    private static final Set<String> STATUSES = Set.of("PENDING", "CLEARED", "CONFIRMED");

    private final JdbcTemplate jdbc;

    public FraudFlagRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID transactionId, List<RuleHit> hits) {
        for (RuleHit hit : hits) {
            jdbc.update("insert into fraud_flags (transaction_id, rule_code, reason) "
                            + "values (cast(? as uuid), ?, ?)",
                    transactionId.toString(), hit.ruleCode(), trim(hit.reason(), 300));
        }
    }

    /**
     * Meninjau SEMUA flag PENDING milik satu transaksi dalam satu statement atomik.
     * Dua operator yang meninjau bersamaan: hanya satu yang mendapat hasil > 0.
     */
    public int review(UUID transactionId, String decision, UUID reviewer, String note) {
        return jdbc.update("""
                update fraud_flags
                   set review_status = ?, reviewed_by = cast(? as uuid), reviewed_at = now(), review_note = ?
                 where transaction_id = cast(? as uuid) and review_status = 'PENDING'
                """, decision, reviewer.toString(), note, transactionId.toString());
    }

    public boolean existsForTransaction(UUID transactionId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from fraud_flags where transaction_id = cast(? as uuid)",
                Integer.class, transactionId.toString());
        return n != null && n > 0;
    }

    public List<FlagView> list(String status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        long offset = (long) Math.max(page, 0) * safeSize;
        String base = """
                select f.id, f.transaction_id, t.reference_no, f.rule_code, f.reason, f.review_status,
                       f.reviewed_by, f.reviewed_at, f.review_note, f.created_at,
                       t.amount, t.from_account_id, t.to_account_id
                  from fraud_flags f join transactions t on t.id = f.transaction_id
                """;
        var mapperFn = (org.springframework.jdbc.core.RowMapper<FlagView>) (rs, i) -> {
            OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
            return new FlagView(rs.getLong("id"), rs.getObject("transaction_id", UUID.class),
                    rs.getString("reference_no"), rs.getString("rule_code"), rs.getString("reason"),
                    rs.getString("review_status"), rs.getObject("reviewed_by", UUID.class),
                    reviewedAt == null ? null : reviewedAt.toInstant(), rs.getString("review_note"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                    rs.getBigDecimal("amount"), rs.getObject("from_account_id", UUID.class),
                    rs.getObject("to_account_id", UUID.class));
        };
        if (status == null || status.isBlank()) {
            return jdbc.query(base + " order by f.id desc limit ? offset ?", mapperFn, safeSize, offset);
        }
        String normalized = status.toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status harus salah satu dari PENDING, CLEARED, CONFIRMED");
        }
        return jdbc.query(base + " where f.review_status = ? order by f.id desc limit ? offset ?",
                mapperFn, normalized, safeSize, offset);
    }

    private static String trim(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
