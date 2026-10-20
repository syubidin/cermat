package id.cermat.account;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.common.NotFoundException;
import id.cermat.user.UserRepository;

@Service
public class AccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountRepository accounts;
    private final UserRepository users;

    public AccountService(AccountRepository accounts, UserRepository users) {
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional
    public Account open(UUID ownerId) {
        if (!users.existsById(ownerId)) {
            throw new NotFoundException("User tidak ditemukan: " + ownerId);
        }
        // Saldo awal selalu 0. Uang masuk hanya lewat transaksi + ledger (Minggu 2).
        return accounts.save(new Account(generateNumber(), ownerId));
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new NotFoundException("Rekening tidak ditemukan: " + id));
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
