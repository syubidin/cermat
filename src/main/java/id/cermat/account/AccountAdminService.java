package id.cermat.account;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.audit.AuditService;
import id.cermat.common.BusinessException;
import id.cermat.common.NotFoundException;

@Service
public class AccountAdminService {

    private final AccountRepository accounts;
    private final AuditService audit;

    public AccountAdminService(AccountRepository accounts, AuditService audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    /** Versi tegas untuk endpoint operator. */
    @Transactional
    public Account freeze(UUID id, String reason) {
        Account a = lock(id);
        if (a.getType() == AccountType.SYSTEM) throw new BusinessException("Rekening sistem tidak boleh dibekukan");
        if (a.getStatus() == AccountStatus.CLOSED) throw new BusinessException("Rekening sudah ditutup");
        if (a.getStatus() == AccountStatus.ACTIVE) {
            a.freeze();
            audit.record("ACCOUNT_FROZEN", "account", id.toString(), Map.of("reason", reason));
        }
        return a;
    }

    /** Versi longgar untuk alur tinjauan fraud: hanya membekukan jika memang masih ACTIVE. */
    @Transactional
    public void freezeIfActive(UUID id, String reason) {
        Account a = lock(id);
        if (a.getType() == AccountType.CUSTOMER && a.getStatus() == AccountStatus.ACTIVE) {
            a.freeze();
            audit.record("ACCOUNT_FROZEN", "account", id.toString(), Map.of("reason", reason));
        }
    }

    @Transactional
    public Account unfreeze(UUID id) {
        Account a = lock(id);
        if (a.getStatus() != AccountStatus.FROZEN) {
            throw new BusinessException("Rekening tidak berstatus FROZEN");
        }
        a.unfreeze();
        audit.record("ACCOUNT_UNFROZEN", "account", id.toString(), Map.of());
        return a;
    }

    /** Kunci baris dulu agar tidak berbalapan dengan transfer yang sedang berjalan. */
    private Account lock(UUID id) {
        List<Account> locked = accounts.lockAllByIdInOrderById(List.of(id));
        if (locked.isEmpty()) throw new NotFoundException("Rekening tidak ditemukan: " + id);
        return locked.get(0);
    }
}
