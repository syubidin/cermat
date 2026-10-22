package id.cermat.transaction;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    /** Untuk aturan VELOCITY. */
    long countByFromAccountIdAndCreatedAtAfter(UUID fromAccountId, Instant since);

    /** Untuk aturan NEW_PAYEE_BIG. */
    boolean existsByFromAccountIdAndToAccountId(UUID fromAccountId, UUID toAccountId);
}
