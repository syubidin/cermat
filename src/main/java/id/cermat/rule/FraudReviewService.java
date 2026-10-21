package id.cermat.rule;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.account.AccountAdminService;
import id.cermat.audit.AuditService;
import id.cermat.common.ConflictException;
import id.cermat.common.NotFoundException;
import id.cermat.transaction.LedgerTransaction;
import id.cermat.transaction.LedgerTransactionRepository;

@Service
public class FraudReviewService {

    public enum Decision { CLEARED, CONFIRMED }

    private final FraudFlagRepository flags;
    private final LedgerTransactionRepository transactions;
    private final AccountAdminService accountAdmin;
    private final AuditService audit;

    public FraudReviewService(FraudFlagRepository flags, LedgerTransactionRepository transactions,
                              AccountAdminService accountAdmin, AuditService audit) {
        this.flags = flags;
        this.transactions = transactions;
        this.accountAdmin = accountAdmin;
        this.audit = audit;
    }

    /**
     * CLEARED   = ternyata wajar, tidak ada tindakan lanjutan.
     * CONFIRMED = fraud terkonfirmasi, rekening pengirim dibekukan.
     * Semua langkah dalam satu transaksi database.
     */
    @Transactional
    public void review(UUID transactionId, Decision decision, UUID reviewerId, String note) {
        int updated = flags.review(transactionId, decision.name(), reviewerId, note);
        if (updated == 0) {
            if (flags.existsForTransaction(transactionId)) {
                throw new ConflictException("Flag untuk transaksi ini sudah ditinjau");
            }
            throw new NotFoundException("Tidak ada flag untuk transaksi: " + transactionId);
        }
        audit.record("FLAG_REVIEWED", "transaction", transactionId.toString(),
                Map.of("decision", decision.name(), "note", note == null ? "" : note));

        if (decision == Decision.CONFIRMED) {
            LedgerTransaction tx = transactions.findById(transactionId)
                    .orElseThrow(() -> new NotFoundException("Transaksi tidak ditemukan: " + transactionId));
            accountAdmin.freezeIfActive(tx.getFromAccountId(),
                    "Fraud terkonfirmasi pada transaksi " + tx.getReferenceNo());
        }
    }
}
