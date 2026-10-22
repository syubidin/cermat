package id.cermat.security;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import id.cermat.common.TooManyRequestsException;

/**
 * Pembatas percobaan login gagal (sliding window), dua lapis:
 *  - per (username + IP): default 5 kegagalan / 5 menit  -> menahan brute force ke satu akun
 *  - per IP             : default 50 kegagalan / 5 menit -> menahan password spraying
 *
 * Kunci memakai pasangan (username, IP), bukan username saja, supaya penyerang tidak bisa
 * mengunci akun korban dari IP lain (serangan DoS lewat lockout).
 * Disimpan di memori: cukup untuk satu instance. Untuk beberapa instance perlu penyimpanan bersama (Redis/DB).
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_KEYS = 10_000;   // batas memori terhadap serangan dengan username acak

    private final Clock clock;
    private final long windowMillis;
    private final int maxPerUserIp;
    private final int maxPerIp;
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock,
                            @Value("${cermat.security.login.window-seconds:300}") long windowSeconds,
                            @Value("${cermat.security.login.max-failures-per-user:5}") int maxPerUserIp,
                            @Value("${cermat.security.login.max-failures-per-ip:50}") int maxPerIp) {
        this.clock = clock;
        this.windowMillis = windowSeconds * 1000;
        this.maxPerUserIp = maxPerUserIp;
        this.maxPerIp = maxPerIp;
    }

    /** Dipanggil SEBELUM verifikasi password (murah, dan tidak membuang CPU BCrypt untuk penyerang). */
    public void checkAllowed(String username, String ip) {
        long retry = Math.max(retryAfterSeconds(userKey(username, ip), maxPerUserIp),
                              retryAfterSeconds(ipKey(ip), maxPerIp));
        if (retry > 0) {
            throw new TooManyRequestsException(retry);
        }
    }

    /** @return true jika kegagalan INI yang membuat kombinasi (username, IP) terkunci. */
    public boolean recordFailure(String username, String ip) {
        boolean lockedNow = add(userKey(username, ip), maxPerUserIp);
        add(ipKey(ip), maxPerIp);
        return lockedNow;
    }

    public void recordSuccess(String username, String ip) {
        failures.remove(userKey(username, ip));
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        long now = clock.millis();
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    // ---------- internal ----------
    private long retryAfterSeconds(String key, int max) {
        Deque<Long> deque = failures.get(key);
        if (deque == null) return 0;
        long now = clock.millis();
        synchronized (deque) {
            prune(deque, now);
            if (deque.size() < max) return 0;
            long unlockAt = deque.peekFirst() + windowMillis;
            return Math.max(1, (unlockAt - now + 999) / 1000);
        }
    }

    private boolean add(String key, int max) {
        if (!failures.containsKey(key) && failures.size() >= MAX_KEYS) {
            purgeExpired();
            if (failures.size() >= MAX_KEYS) return false;   // penuh: lapis per-IP tetap bekerja
        }
        Deque<Long> deque = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        long now = clock.millis();
        synchronized (deque) {
            prune(deque, now);
            deque.addLast(now);
            return deque.size() == max;
        }
    }

    private void prune(Deque<Long> deque, long now) {
        while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
            deque.pollFirst();
        }
    }

    private static String userKey(String username, String ip) {
        String name = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return "u:" + name + "|" + (ip == null ? "unknown" : ip);
    }

    private static String ipKey(String ip) {
        return "i:" + (ip == null ? "unknown" : ip);
    }
}
