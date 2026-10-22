package id.cermat.recon;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReconRepository {

    public record InternalTx(String referenceNo, BigDecimal amount) {}

    public record ItemRow(String referenceNo, BigDecimal partnerAmount, BigDecimal internalAmount, String result) {}

    public record ItemView(long id, String referenceNo, BigDecimal partnerAmount, BigDecimal internalAmount,
                           String result, BigDecimal difference) {}

    public record BatchView(UUID id, String partnerName, String fileName, UUID partnerAccountId,
                            LocalDate periodFrom, LocalDate periodTo, int totalRows, int matched,
                            int amountDiff, int missingInternal, int missingPartner, int duplicates,
                            UUID uploadedBy, Instant createdAt) {}

    public static final Set<String> RESULTS =
            Set.of("MATCH", "AMOUNT_DIFF", "MISSING_INTERNAL", "MISSING_PARTNER", "DUPLICATE_PARTNER");

    private static final String BATCH_SELECT = """
            select b.id, b.partner_name, b.file_name, b.partner_account_id, b.period_from, b.period_to,
                   b.total_rows, b.uploaded_by, b.created_at,
                   count(i.id) filter (where i.result = 'MATCH')             as c_match,
                   count(i.id) filter (where i.result = 'AMOUNT_DIFF')       as c_diff,
                   count(i.id) filter (where i.result = 'MISSING_INTERNAL')  as c_mi,
                   count(i.id) filter (where i.result = 'MISSING_PARTNER')   as c_mp,
                   count(i.id) filter (where i.result = 'DUPLICATE_PARTNER') as c_dup
              from recon_batches b left join recon_items i on i.batch_id = b.id
            """;

    private static final RowMapper<BatchView> BATCH_MAPPER = (rs, n) -> new BatchView(
            rs.getObject("id", UUID.class), rs.getString("partner_name"), rs.getString("file_name"),
            rs.getObject("partner_account_id", UUID.class),
            rs.getObject("period_from", LocalDate.class), rs.getObject("period_to", LocalDate.class),
            rs.getInt("total_rows"), rs.getInt("c_match"), rs.getInt("c_diff"), rs.getInt("c_mi"),
            rs.getInt("c_mp"), rs.getInt("c_dup"), rs.getObject("uploaded_by", UUID.class),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public ReconRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Transaksi internal yang melibatkan rekening mitra pada rentang waktu [start, endExclusive). */
    public List<InternalTx> findInternal(UUID accountId, Instant start, Instant endExclusive) {
        return jdbc.query("""
                select reference_no, amount from transactions
                 where (from_account_id = cast(? as uuid) or to_account_id = cast(? as uuid))
                   and created_at >= ? and created_at < ?
                """,
                (rs, n) -> new InternalTx(rs.getString("reference_no"), rs.getBigDecimal("amount")),
                accountId.toString(), accountId.toString(),
                OffsetDateTime.ofInstant(start, ZoneOffset.UTC), OffsetDateTime.ofInstant(endExclusive, ZoneOffset.UTC));
    }

    public Optional<UUID> findBatchIdBySameFile(String sha256, UUID accountId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                select id from recon_batches
                 where file_sha256 = ? and partner_account_id = cast(? as uuid)
                   and period_from = ? and period_to = ?
                """,
                (rs, n) -> rs.getObject("id", UUID.class),
                sha256, accountId.toString(), java.sql.Date.valueOf(from), java.sql.Date.valueOf(to))
                .stream().findFirst();
    }

    public void insertBatch(UUID id, String partnerName, String fileName, UUID uploadedBy, int totalRows,
                            int matched, int missing, int mismatched, int duplicates,
                            UUID partnerAccountId, LocalDate from, LocalDate to, String sha256) {
        jdbc.update("""
                insert into recon_batches (id, partner_name, file_name, uploaded_by, total_rows, matched,
                                           missing, mismatched, duplicates, partner_account_id,
                                           period_from, period_to, file_sha256)
                values (cast(? as uuid), ?, ?, cast(? as uuid), ?, ?, ?, ?, ?, cast(? as uuid), ?, ?, ?)
                """,
                id.toString(), partnerName, fileName, uploadedBy.toString(), totalRows, matched,
                missing, mismatched, duplicates, partnerAccountId.toString(),
                java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), sha256);
    }

    public void insertItems(UUID batchId, List<ItemRow> items) {
        jdbc.batchUpdate("""
                insert into recon_items (batch_id, reference_no, partner_amount, internal_amount, result)
                values (cast(? as uuid), ?, ?, ?, ?)
                """, items, 1000, (ps, item) -> {
            ps.setString(1, batchId.toString());
            ps.setString(2, item.referenceNo());
            ps.setBigDecimal(3, item.partnerAmount());
            ps.setBigDecimal(4, item.internalAmount());
            ps.setString(5, item.result());
        });
    }

    public Optional<BatchView> findBatch(UUID id) {
        return jdbc.query(BATCH_SELECT + " where b.id = cast(? as uuid) group by b.id",
                BATCH_MAPPER, id.toString()).stream().findFirst();
    }

    public List<BatchView> listBatches(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        long offset = (long) Math.max(page, 0) * safeSize;
        return jdbc.query(BATCH_SELECT + " group by b.id order by b.created_at desc, b.id limit ? offset ?",
                BATCH_MAPPER, safeSize, offset);
    }

    public List<ItemView> listItems(UUID batchId, String result, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 500);
        long offset = (long) Math.max(page, 0) * safeSize;
        RowMapper<ItemView> mapper = (rs, n) -> {
            BigDecimal partner = rs.getBigDecimal("partner_amount");
            BigDecimal internal = rs.getBigDecimal("internal_amount");
            BigDecimal diff = (partner != null && internal != null) ? partner.subtract(internal) : null;
            return new ItemView(rs.getLong("id"), rs.getString("reference_no"), partner, internal,
                    rs.getString("result"), diff);
        };
        String base = "select id, reference_no, partner_amount, internal_amount, result from recon_items "
                + "where batch_id = cast(? as uuid)";
        if (result == null || result.isBlank()) {
            return jdbc.query(base + " order by id limit ? offset ?", mapper, batchId.toString(), safeSize, offset);
        }
        String normalized = result.toUpperCase();
        if (!RESULTS.contains(normalized)) {
            throw new IllegalArgumentException("result harus salah satu dari " + RESULTS);
        }
        return jdbc.query(base + " and result = ? order by id limit ? offset ?",
                mapper, batchId.toString(), normalized, safeSize, offset);
    }
}
