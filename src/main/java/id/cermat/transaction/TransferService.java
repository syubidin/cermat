package id.cermat.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.account.Account;
import id.cermat.account.AccountRepository;
import id.cermat.account.AccountStatus;
import id.cermat.audit.AuditService;
import id.cermat.common.BusinessException;
import id.cermat.common.NotFoundException;
import id.cermat.idempotency.Hashing;
import id.cermat.idempotency.IdempotencyKey;
import id.cermat.idempotency.IdempotencyKeyRepository;
import id.cermat.ledger.Direction;
import id.cermat.ledger.LedgerEntry;
import id.cermat.ledger.LedgerEntryRepository;
import id.cermat.rule.FraudFlagRepository;
import id.cermat.rule.RuleContext;
import id.cermat.rule.RuleEngine;
import id.cermat.rule.RuleHit;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final AuditService audit;
    private final RuleEngine ruleEngine;
    private final FraudFlagRepository fraudFlags;
    private final CashPool cashPool;

    public TransferService(AccountRepository accounts, LedgerTransactionRepository transactions,
                           LedgerEntryRepository entries, IdempotencyKeyRepository idempotencyKeys,
                           AuditService audit, RuleEngine ruleEngine, FraudFlagRepository fraudFlags,
                           CashPool cashPool) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotencyKeys = idempotencyKeys;
        this.audit = audit;
        this.ruleEngine = ruleEngine;
        this.fraudFlags = fraudFlags;
        this.cashPool = cashPool;
    }

    /**
     * Seluruh langkah berjalan dalam SATU transaksi database (atomic), termasuk evaluasi aturan,
     * flag fraud, dan jejak audit. Gagal di tengah jalan = semuanya batal.
     */
    @Transactional
    public TransferResult transfer(String idemKey, UUID fromId, UUID toId,
                                   BigDecimal rawAmount, String description) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key wajib diisi (maks 100 karakter)");
        }
        if (fromId.equals(toId)) {
            throw new BusinessException("Rekening asal dan tujuan tidak boleh sama");
        }
        BigDecimal amount = rawAmount.setScale(2);
        boolean fromCash = cashPool.isCash(fromId);

        // Shard kas mana pun yang dipakai, isi request yang SAMA harus menghasilkan hash yang SAMA.
        String fromPart = fromCash ? "CASH" : fromId.toString();
        String hash = Hashing.sha256(fromPart + "|" + toId + "|" + amount.toPlainString()
                + "|" + (description == null ? "" : description));

        // 1) Klaim key secara atomik. Jika sudah ada: jawab ulang, JANGAN proses lagi.
        if (idempotencyKeys.insertIfAbsent(idemKey, hash) == 0) {
            return replay(idemKey, hash);
        }

        // 2) Kunci kedua rekening dengan urutan tetap, lalu baca saldo terbaru.
        List<Account> locked = accounts.lockAllByIdInOrderById(List.of(fromId, toId));
        Map<UUID, Account> byId = locked.stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        Account from = byId.get(fromId);
        Account to = byId.get(toId);
        if (from == null) throw new NotFoundException("Rekening asal tidak ditemukan: " + fromId);
        if (to == null) throw new NotFoundException("Rekening tujuan tidak ditemukan: " + toId);

        // 3) Validasi aturan bisnis.
        if (from.getStatus() != AccountStatus.ACTIVE) throw new BusinessException("Rekening asal tidak aktif");
        if (to.getStatus() != AccountStatus.ACTIVE) throw new BusinessException("Rekening tujuan tidak aktif");
        if (!from.getCurrency().equals(to.getCurrency())) throw new BusinessException("Mata uang berbeda");

        // 4) Deteksi anomali (post-transaction monitoring): transaksi TETAP berjalan, hanya ditandai.
        List<RuleHit> hits = fromCash
                ? List.of()
                : ruleEngine.evaluate(new RuleContext(fromId, toId, amount, Instant.now()));
        TxStatus status = hits.isEmpty() ? TxStatus.SUCCESS : TxStatus.FLAGGED;

        // 5) Ubah saldo (debit melempar BusinessException jika saldo kurang).
        from.debit(amount);
        to.credit(amount);

        // 6) Tulis transaksi + dua entri ledger (debit dan kredit selalu seimbang),
        //    lalu tautkan key idempotency ke transaksi ini.
        LedgerTransaction tx = transactions.saveAndFlush(new LedgerTransaction(
                newReference(), idemKey, fromId, toId, amount, status, description));
        entries.save(new LedgerEntry(tx.getId(), fromId, Direction.DEBIT, amount, from.getBalance()));
        entries.save(new LedgerEntry(tx.getId(), toId, Direction.CREDIT, amount, to.getBalance()));
        idempotencyKeys.attachTransaction(idemKey, tx.getId());

        // 7) Flag fraud + jejak audit, di transaksi yang sama.
        if (!hits.isEmpty()) {
            fraudFlags.insert(tx.getId(), hits);
            audit.record("TRANSACTION_FLAGGED", "transaction", tx.getId().toString(), Map.of(
                    "rules", hits.stream().map(RuleHit::ruleCode).toList(),
                    "amount", amount.toPlainString()));
        }
        audit.record(fromCash ? "DEPOSIT" : "TRANSFER", "transaction",
                tx.getId().toString(),
                Map.of("referenceNo", tx.getReferenceNo(), "from", fromId.toString(),
                        "to", toId.toString(), "amount", amount.toPlainString()));
        return new TransferResult(tx, false);
    }

    /** Setoran = transfer dari salah satu shard kas sistem ke rekening nasabah. */
    @Transactional
    public TransferResult deposit(String idemKey, UUID accountId, BigDecimal amount, String description) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key wajib diisi (maks 100 karakter)");
        }
        return transfer(idemKey, cashPool.shardFor(idemKey), accountId, amount,
                description == null ? "Setoran" : description);
    }

    private TransferResult replay(String idemKey, String hash) {
        IdempotencyKey existing = idempotencyKeys.findById(idemKey)
                .orElseThrow(() -> new IllegalStateException("Key hilang setelah konflik: " + idemKey));
        if (!existing.getRequestHash().equals(hash)) {
            throw new BusinessException("Idempotency-Key sudah dipakai untuk request yang berbeda");
        }
        if (existing.getTransactionId() == null) {
            throw new IllegalStateException("Key belum tertaut ke transaksi: " + idemKey);
        }
        LedgerTransaction tx = transactions.findById(existing.getTransactionId())
                .orElseThrow(() -> new IllegalStateException("Transaksi untuk key tidak ditemukan: " + idemKey));
        return new TransferResult(tx, true);
    }

    private String newReference() {
        return "TRX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
