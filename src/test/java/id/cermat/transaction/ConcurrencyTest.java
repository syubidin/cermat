package id.cermat.transaction;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import id.cermat.account.AccountService;
import id.cermat.common.BusinessException;
import id.cermat.user.Role;
import id.cermat.user.User;
import id.cermat.user.UserService;

/**
 * Bukti konkurensi: banyak transfer sekaligus, tetapi uang tetap konsisten.
 * Dipanggil langsung ke service dengan banyak thread (tiap thread = transaksi DB sendiri).
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrencyTest {

    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;

    enum Kind { OK, BUSINESS, ERROR }

    record Outcome(Kind kind, TransferResult result, Throwable error) {}

    // ---------- helper ----------
    private UUID newAccount() {
        User u = users.register("c-" + UUID.randomUUID().toString().substring(0, 12), "password123", Role.NASABAH);
        return accounts.open(u.getId()).getId();
    }

    private String key() { return "k-" + UUID.randomUUID(); }

    private void fund(UUID account, String amount) {
        transfers.deposit(key(), account, new BigDecimal(amount), null);
    }

    private Outcome attempt(Callable<TransferResult> call) {
        try {
            return new Outcome(Kind.OK, call.call(), null);
        } catch (BusinessException e) {
            return new Outcome(Kind.BUSINESS, null, e);
        } catch (Throwable t) {
            return new Outcome(Kind.ERROR, null, t);
        }
    }

    /** Semua task ditahan di gerbang start, lalu dilepas serentak. */
    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> task : tasks) {
            futures.add(pool.submit(() -> { gate.await(); return task.call(); }));
        }
        gate.countDown();
        List<Outcome> results = new ArrayList<>();
        for (Future<Outcome> f : futures) results.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        return results;
    }

    private long count(List<Outcome> all, Kind kind) {
        return all.stream().filter(o -> o.kind() == kind).count();
    }

    private String firstError(List<Outcome> all) {
        return all.stream().filter(o -> o.kind() == Kind.ERROR)
                .map(o -> String.valueOf(o.error())).findFirst().orElse("");
    }

    private void assertBalance(UUID id, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(accounts.get(id).getBalance()),
                "Saldo seharusnya " + expected + " tetapi " + accounts.get(id).getBalance());
    }

    // ---------- test ----------

    @Test
    void concurrentTransfers_neverOverdraw() throws Exception {
        UUID a = newAccount(), b = newAccount();
        fund(a, "100000");

        // 150 transfer @1.000 dari saldo 100.000: tepat 100 boleh berhasil, 50 harus ditolak.
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String k = key();
            tasks.add(() -> attempt(() -> transfers.transfer(k, a, b, new BigDecimal("1000"), null)));
        }
        List<Outcome> res = runConcurrently(tasks);

        assertEquals(0, count(res, Kind.ERROR), "Error tak terduga: " + firstError(res));
        assertEquals(100, count(res, Kind.OK));
        assertEquals(50, count(res, Kind.BUSINESS));
        assertBalance(a, "0");        // tidak pernah minus
        assertBalance(b, "100000");   // semua uang berpindah, tidak ada yang hilang
    }

    @Test
    void opposingTransfers_doNotDeadlock() throws Exception {
        UUID a = newAccount(), b = newAccount();
        fund(a, "100000");
        fund(b, "100000");

        // 50 transfer A->B dan 50 transfer B->A bersamaan: tanpa urutan kunci tetap, ini rawan deadlock.
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            String k1 = key(), k2 = key();
            tasks.add(() -> attempt(() -> transfers.transfer(k1, a, b, new BigDecimal("1000"), null)));
            tasks.add(() -> attempt(() -> transfers.transfer(k2, b, a, new BigDecimal("1000"), null)));
        }
        List<Outcome> res = runConcurrently(tasks);

        assertEquals(0, count(res, Kind.ERROR), "Error tak terduga (deadlock?): " + firstError(res));
        assertEquals(100, count(res, Kind.OK));
        assertBalance(a, "100000");
        assertBalance(b, "100000");
    }

    @Test
    void sameIdempotencyKey_sentConcurrently_appliesExactlyOnce() throws Exception {
        UUID a = newAccount(), b = newAccount();
        fund(a, "100000");
        String sharedKey = key();

        // 20 request IDENTIK (key sama) serentak, seperti user yang menekan tombol berkali-kali.
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> attempt(() -> transfers.transfer(sharedKey, a, b, new BigDecimal("1000"), "klik ganda")));
        }
        List<Outcome> res = runConcurrently(tasks);

        assertEquals(0, count(res, Kind.ERROR), "Error tak terduga: " + firstError(res));
        assertEquals(20, count(res, Kind.OK));   // semua mendapat jawaban

        long processed = res.stream().filter(o -> !o.result().replayed()).count();
        assertEquals(1, processed, "Hanya satu request yang boleh benar-benar memproses");

        long distinctTx = res.stream().map(o -> o.result().transaction().getId()).distinct().count();
        assertEquals(1, distinctTx, "Semua jawaban harus merujuk transaksi yang sama");

        assertBalance(a, "99000");   // terpotong sekali, bukan 20 kali
        assertBalance(b, "1000");

        UUID txId = res.get(0).result().transaction().getId();
        Integer entryCount = jdbc.queryForObject(
                "select count(*) from ledger_entries where transaction_id = ?", Integer.class, txId);
        assertEquals(2, entryCount);  // tepat satu debit + satu kredit
    }

    @Test
    void sequentialReplay_returnsSameTransaction_andDifferentPayloadIsRejected() {
        UUID a = newAccount(), b = newAccount();
        fund(a, "10000");
        String k = key();

        TransferResult first = transfers.transfer(k, a, b, new BigDecimal("1000"), null);
        TransferResult second = transfers.transfer(k, a, b, new BigDecimal("1000"), null);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.transaction().getId(), second.transaction().getId());

        assertThrows(BusinessException.class,
                () -> transfers.transfer(k, a, b, new BigDecimal("2000"), null));
        assertBalance(a, "9000");
    }

    @Test
    void failedRequest_doesNotConsumeTheKey() {
        UUID a = newAccount(), b = newAccount();
        String k = key();

        // Saldo kosong: ditolak, dan transaksi DB di-rollback (key ikut batal).
        assertThrows(BusinessException.class, () -> transfers.transfer(k, a, b, new BigDecimal("1000"), null));

        fund(a, "5000");
        TransferResult retry = transfers.transfer(k, a, b, new BigDecimal("1000"), null);

        assertFalse(retry.replayed());   // diproses sungguhan, bukan jawaban ulang dari kegagalan
        assertBalance(a, "4000");
        assertBalance(b, "1000");
    }
}
