package id.cermat.idempotency;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Menghapus idempotency key yang sudah melewati masa berlaku (default 24 jam).
 * Setelah kedaluwarsa, key yang sama dianggap request BARU. Transaksi lamanya tetap utuh di ledger.
 * Dihapus per 1.000 baris agar tidak menahan kunci tabel lama-lama.
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final Duration retention;

    public IdempotencyCleanupJob(JdbcTemplate jdbc,
                                 @Value("${cermat.idempotency.retention-hours:24}") long retentionHours) {
        this.jdbc = jdbc;
        this.retention = Duration.ofHours(retentionHours);
    }

    @Scheduled(cron = "${cermat.idempotency.cleanup-cron:0 15 * * * *}")   // tiap jam, menit ke-15
    public int cleanup() {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minus(retention);
        int total = 0;
        int deleted;
        do {
            deleted = jdbc.update("""
                    delete from idempotency_keys
                     where idem_key in (select idem_key from idempotency_keys
                                         where created_at < ? limit 1000)
                    """, cutoff);
            total += deleted;
        } while (deleted > 0);
        if (total > 0) {
            log.info("Idempotency key kedaluwarsa dihapus: {}", total);
        }
        return total;
    }
}
