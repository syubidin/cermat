package id.cermat.account;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.audit.AuditService;
import id.cermat.common.NotFoundException;
import id.cermat.security.AuthUser;
import id.cermat.user.Role;
import id.cermat.user.UserRepository;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final UserRepository users;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, UserRepository users, AuditService audit) {
        this.accounts = accounts;
        this.users = users;
        this.audit = audit;
    }

    /** Internal (tanpa pengecekan pemanggil). Dipakai oleh versi ber-otorisasi di bawah dan oleh test. */
    @Transactional
    public Account open(UUID ownerId) {
        if (!users.existsById(ownerId)) {
            throw new NotFoundException("User tidak ditemukan: " + ownerId);
        }
        // Saldo awal selalu 0. Uang masuk hanya lewat transaksi + ledger.
        Account saved = accounts.save(new Account(generateNumber(), ownerId));
        audit.record("ACCOUNT_OPENED", "account", saved.getId().toString(),
                Map.of("accountNumber", saved.getAccountNumber(), "ownerId", ownerId.toString()));
        return saved;
    }

    /** NASABAH: hanya untuk dirinya sendiri. OPERATOR: untuk nasabah mana pun (ownerId wajib). */
    @Transactional
    public Account open(AuthUser actor, UUID requestedOwnerId) {
        if (actor.role() == Role.NASABAH) {
            if (requestedOwnerId != null && !requestedOwnerId.equals(actor.id())) {
                throw new AccessDeniedException("Nasabah mencoba membuka rekening atas nama orang lain");
            }
            return open(actor.id());
        }
        if (actor.role() == Role.OPERATOR) {
            if (requestedOwnerId == null) {
                throw new IllegalArgumentException("ownerId wajib diisi oleh operator");
            }
            return open(requestedOwnerId);
        }
        throw new AccessDeniedException("Role " + actor.role() + " tidak boleh membuka rekening");
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Rekening tidak ditemukan: " + id));
    }

    /**
     * Pencegah IDOR/BOLA: nasabah yang meminta rekening orang lain mendapat 404 (bukan 403),
     * sehingga keberadaan rekening itu tidak bocor. OPERATOR dan AUDITOR boleh membaca.
     */
    @Transactional(readOnly = true)
    public Account getAuthorized(UUID id, AuthUser me) {
        Account account = get(id);
        if (me.role() == Role.NASABAH && !account.getOwnerId().equals(me.id())) {
            throw new NotFoundException("Rekening tidak ditemukan: " + id);
        }
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> listFor(AuthUser me, UUID requestedOwnerId) {
        if (me.role() == Role.NASABAH) {
            if (requestedOwnerId != null && !requestedOwnerId.equals(me.id())) {
                throw new AccessDeniedException("Nasabah mencoba melihat rekening orang lain");
            }
            return accounts.findByOwnerId(me.id());
        }
        if (requestedOwnerId == null) {
            throw new IllegalArgumentException("ownerId wajib diisi");
        }
        return accounts.findByOwnerId(requestedOwnerId);
    }

    @Transactional(readOnly = true)
    public List<Account> listByOwner(UUID ownerId) {
        return accounts.findByOwnerId(ownerId);
    }

    private String generateNumber() {
        String number;
        do {
            number = String.format("%010d", RANDOM.nextLong(10_000_000_000L));
        } while (accounts.existsByAccountNumber(number));
        return number;
    }
}
