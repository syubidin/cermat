package id.cermat.hardening;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import id.cermat.account.AccountService;
import id.cermat.transaction.TransferService;
import id.cermat.user.Role;
import id.cermat.user.User;
import id.cermat.user.UserService;

/**
 * Benchmark throughput setoran. DILEWATI secara default (tidak ikut mvn test biasa).
 *
 * Jalankan dua kali lalu bandingkan:
 *   mvn test -Dtest=CashBenchmarkTest -Dcermat.benchmark=true -Dcermat.cash.shards=1
 *   mvn test -Dtest=CashBenchmarkTest -Dcermat.benchmark=true -Dcermat.cash.shards=16
 *
 * (PowerShell: beri tanda kutip pada tiap -D, mis. "-Dcermat.benchmark=true".)
 * Jika properti tidak terbaca dan test dilewati, pakai: -DargLine="-Dcermat.benchmark=true -Dcermat.cash.shards=1"
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "cermat.benchmark", matches = "true")
class CashBenchmarkTest {

    static final int THREADS = 10;      // sama dengan ukuran default connection pool (Hikari = 10)
    static final int WARMUP = 50;
    static final int MEASURED = 400;

    @Autowired TransferService transfers;
    @Autowired AccountService accounts;
    @Autowired UserService users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;

    @Test
    void depositThroughput() throws Exception {
        User owner = users.register("bench-" + UUID.randomUUID().toString().substring(0, 10),
                "password123", Role.NASABAH);
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < 200; i++) targets.add(accounts.open(owner.getId()).getId());

        run(targets, WARMUP);   // pemanasan (JIT, connection pool), tidak diukur

        long start = System.nanoTime();
        List<Long> latencies = run(targets, MEASURED);
        long totalMs = (System.nanoTime() - start) / 1_000_000;

        Collections.sort(latencies);
        double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);

        System.out.printf("%n=== BENCHMARK SETORAN | shards=%s | %d setoran | %d thread ===%n"
                        + "waktu total : %d ms%nthroughput  : %.1f setoran/detik%n"
                        + "latensi     : rata-rata %.1f ms | p95 %d ms%n%n",
                env.getProperty("cermat.cash.shards", "16"), MEASURED, THREADS,
                totalMs, MEASURED * 1000.0 / totalMs, avg, p95);

        // Kebenaran tetap yang utama: tidak ada uang yang hilang atau tercipta.
        BigDecimal deposited = jdbc.queryForObject(
                "select sum(balance) from accounts where owner_id = ?", BigDecimal.class, owner.getId());
        assertEquals(0, new BigDecimal((WARMUP + MEASURED) * 1000).compareTo(deposited));
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance),0) from accounts", BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(total));
    }

    private List<Long> run(List<UUID> targets, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                String key = "bench-" + UUID.randomUUID();
                long begin = System.nanoTime();
                transfers.deposit(key, targets.get(index % targets.size()), new BigDecimal("1000"), null);
                return (System.nanoTime() - begin) / 1_000_000;
            }));
        }
        List<Long> latencies = new ArrayList<>();
        for (Future<Long> f : futures) latencies.add(f.get(120, TimeUnit.SECONDS));
        pool.shutdown();
        return latencies;
    }
}
