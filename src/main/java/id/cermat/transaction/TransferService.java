package id.cermat.transaction;

import java.math.BigDecimal;
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
import id.cermat.common.BusinessException;
import id.cermat.common.ConflictException;
import id.cermat.common.NotFoundException;
import id.cermat.idempotency.Hashing;
import id.cermat.idempotency.IdempotencyKey;
import id.cermat.idempotency.IdempotencyKeyRepository;
import id.cermat.ledger.Direction;
import id.cermat.ledger.LedgerEntry;
import id.cermat.ledger.LedgerEntryRepository;

@Service
public class TransferService {

    /** Rekening kas internal bank (di-seed oleh migrasi V2). */
    public static final UUID SYSTEM_CASH_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final AccountRepository accounts;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final IdempotencyKeyRepository idempotencyKeys;

    public TransferService(AccountRepository accounts, LedgerTransactionRepository transactions,
                           LedgerEntryRepository entries, IdempotencyKeyRepository idempotencyKeys) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.idempotencyKeys = idempotencyKeys;
    }

    /**
     * Seluruh langkah berjalan dalam SATU transaksi database:
     * jika satu langkah gagal, semuanya dibatalkan (atomic).
     */
    @Transactional
    public LedgerTransaction transfer(String idemKey, UUID fromId, UUID toId,
                                      BigDecimal rawAmount, String description) {
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key wajib diisi (maks 100 karakter)");
        }
        if (fromId.equals(toId)) {
            throw new BusinessException("Rekening asal dan tujuan tidak boleh sama");
        }
        BigDecimal amount = rawAmount.setScale(2);

        // 1) Catat idempotency key. (Minggu 3: replay respons lama + cek request_hash.)
        if (idempotencyKeys.existsById(idemKey)) {
            throw new ConflictException("Idempotency-Key sudah pernah dipakai: " + idemKey);
        }
        String hash = Hashing.sha256(fromId + "|" + toId + "|" + amount.toPlainString());
        idempotencyKeys.saveAndFlush(new IdempotencyKey(idemKey, hash));

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

        // 4) Ubah saldo (debit melempar BusinessException jika saldo kurang).
        from.debit(amount);
        to.credit(amount);

        // 5) Tulis transaksi + dua entri ledger (debit dan kredit selalu seimbang).
        LedgerTransaction tx = transactions.saveAndFlush(new LedgerTransaction(
                newReference(), idemKey, fromId, toId, amount, TxStatus.SUCCESS, description));
        entries.save(new LedgerEntry(tx.getId(), fromId, Direction.DEBIT, amount, from.getBalance()));
        entries.save(new LedgerEntry(tx.getId(), toId, Direction.CREDIT, amount, to.getBalance()));
        return tx;
    }

    /** Setoran = transfer dari kas sistem ke rekening nasabah. */
    @Transactional
    public LedgerTransaction deposit(String idemKey, UUID accountId, BigDecimal amount, String description) {
        return transfer(idemKey, SYSTEM_CASH_ID, accountId, amount,
                description == null ? "Setoran" : description);
    }

    private String newReference() {
        return "TRX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
