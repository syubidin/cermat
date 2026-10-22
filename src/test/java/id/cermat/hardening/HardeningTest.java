package id.cermat.hardening;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import id.cermat.account.AccountService;
import id.cermat.idempotency.IdempotencyCleanupJob;
import id.cermat.transaction.CashPool;
import id.cermat.transaction.TransferResult;
import id.cermat.transaction.TransferService;
import id.cermat.user.Role;
import id.cermat.user.User;
import id.cermat.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HardeningTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired AccountService accounts;
    @Autowired TransferService transfers;
    @Autowired CashPool cashPool;
    @Autowired IdempotencyCleanupJob cleanup;

    private String uid() { return UUID.randomUUID().toString().substring(0, 10); }

    private UUID newAccount() {
        User u = users.register("h-" + uid(), "password123", Role.NASABAH);
        return accounts.open(u.getId()).getId();
    }

    private void assertBalance(UUID id, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(accounts.get(id).getBalance()),
                "Saldo seharusnya " + expected + " tetapi " + accounts.get(id).getBalance());
    }

    // ---------- 1) kas sistem tersebar ----------
    @Test
    void deposits_areSpreadAcrossCashShards_andMoneyIsConserved() {
        UUID acc = newAccount();
        Set<UUID> shardsUsed = new HashSet<>();

        for (int i = 0; i < 40; i++) {
            TransferResult r = transfers.deposit("shard-" + UUID.randomUUID(), acc, new BigDecimal("100"), null);
            UUID from = r.transaction().getFromAccountId();
            assertTrue(cashPool.isCash(from), "Setoran harus berasal dari rekening kas");
            shardsUsed.add(from);
        }

        assertTrue(shardsUsed.size() > 1, "Setoran harus tersebar ke lebih dari satu shard, dapat: " + shardsUsed.size());
        assertBalance(acc, "4000");

        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance),0) from accounts", BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(total), "Total seluruh saldo (termasuk semua shard kas) harus 0");
    }

    @Test
    void depositReplay_staysIdempotent_whenCashIsSharded() {
        UUID acc = newAccount();
        String key = "dep-" + UUID.randomUUID();

        TransferResult first = transfers.deposit(key, acc, new BigDecimal("1000"), null);
        TransferResult second = transfers.deposit(key, acc, new BigDecimal("1000"), null);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transaction().getId(), second.transaction().getId());
        assertEquals(first.transaction().getFromAccountId(), second.transaction().getFromAccountId());
        assertBalance(acc, "1000");   // hanya masuk sekali
    }

    // ---------- 2) idempotency key kedaluwarsa ----------
    @Test
    void cleanup_removesOnlyExpiredKeys_andKeepsTransactions() {
        UUID acc = newAccount();
        String oldKey = "old-" + UUID.randomUUID();
        String freshKey = "fresh-" + UUID.randomUUID();

        TransferResult original = transfers.deposit(oldKey, acc, new BigDecimal("1000"), null);
        transfers.deposit(freshKey, acc, new BigDecimal("1000"), null);

        jdbc.update("update idempotency_keys set created_at = now() - interval '48 hours' where idem_key = ?", oldKey);

        int deleted = cleanup.cleanup();
        assertTrue(deleted >= 1);

        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, oldKey));
        assertEquals(1, jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?",
                Integer.class, freshKey));

        // Transaksi lama TIDAK ikut terhapus: ledger tetap utuh.
        assertEquals(1, jdbc.queryForObject("select count(*) from transactions where id = ?",
                Integer.class, original.transaction().getId()));

        // Setelah kedaluwarsa, key yang sama dianggap request BARU.
        TransferResult again = transfers.deposit(oldKey, acc, new BigDecimal("1000"), null);
        assertFalse(again.replayed());
        assertNotEquals(original.transaction().getId(), again.transaction().getId());
        assertBalance(acc, "3000");
    }

    // ---------- 3) rate limiting login ----------
    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void login_isRateLimited_perUserAndIp_withoutLockingOtherUsers() throws Exception {
        String victim = "rl-" + uid();
        String other = "rl2-" + uid();
        users.register(victim, "password123", Role.NASABAH);
        users.register(other, "password123", Role.NASABAH);

        for (int i = 0; i < 5; i++) {
            login(victim, "salah-banget-" + i).andExpect(status().isUnauthorized());
        }

        // Setelah 5 kegagalan, bahkan password yang BENAR ditolak sementara (429) lengkap dengan Retry-After.
        login(victim, "password123")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Pengguna lain dari IP yang sama tetap bisa login.
        login(other, "password123").andExpect(status().isOk());

        // Kunci tercatat satu kali saja di audit log (tidak membanjiri).
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'LOGIN_LOCKED' and entity_id = ?",
                Integer.class, victim));
    }
}
