package id.cermat.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import id.cermat.common.TooManyRequestsException;
import id.cermat.support.MutableClock;

/** Unit test murni (tanpa Spring, tanpa database): cepat dan deterministik. */
class LoginRateLimiterTest {

    MutableClock clock;
    LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"));
        limiter = new LoginRateLimiter(clock, 300, 5, 50);   // jendela 5 menit, 5 per user+IP, 50 per IP
    }

    private void failTimes(String user, String ip, int times) {
        for (int i = 0; i < times; i++) limiter.recordFailure(user, ip);
    }

    @Test
    void allowsUpToLimit_thenBlocksOnFifthFailure() {
        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure("budi", "1.1.1.1"));
            limiter.checkAllowed("budi", "1.1.1.1");   // belum terkunci
        }
        assertTrue(limiter.recordFailure("budi", "1.1.1.1"), "Kegagalan ke-5 yang memasang kunci");
        assertThrows(TooManyRequestsException.class, () -> limiter.checkAllowed("budi", "1.1.1.1"));
    }

    @Test
    void unlocksAfterWindowPasses() {
        failTimes("budi", "1.1.1.1", 5);
        assertThrows(TooManyRequestsException.class, () -> limiter.checkAllowed("budi", "1.1.1.1"));

        clock.advanceSeconds(301);

        assertDoesNotThrow(() -> limiter.checkAllowed("budi", "1.1.1.1"));
    }

    @Test
    void retryAfterReflectsRemainingTime() {
        failTimes("budi", "1.1.1.1", 5);
        clock.advanceSeconds(100);

        TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
                () -> limiter.checkAllowed("budi", "1.1.1.1"));

        assertEquals(200, e.getRetryAfterSeconds());   // 300 - 100
    }

    @Test
    void successfulLoginResetsUserCounter() {
        failTimes("budi", "1.1.1.1", 4);
        limiter.recordSuccess("budi", "1.1.1.1");

        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure("budi", "1.1.1.1"));
        }
        assertDoesNotThrow(() -> limiter.checkAllowed("budi", "1.1.1.1"));
    }

    @Test
    void lockoutDoesNotAffectOtherUserOrOtherIp() {
        failTimes("budi", "1.1.1.1", 5);

        assertDoesNotThrow(() -> limiter.checkAllowed("budi", "2.2.2.2"));   // IP lain: korban tidak bisa dikunci dari luar
        assertDoesNotThrow(() -> limiter.checkAllowed("siti", "1.1.1.1"));   // user lain, IP sama
    }

    @Test
    void perIpLimitStopsPasswordSpraying() {
        for (int i = 0; i < 50; i++) {
            limiter.recordFailure("user" + i, "9.9.9.9");   // tiap username hanya sekali
        }
        assertThrows(TooManyRequestsException.class, () -> limiter.checkAllowed("orangBaru", "9.9.9.9"));
        assertDoesNotThrow(() -> limiter.checkAllowed("orangBaru", "8.8.8.8"));
    }

    @Test
    void usernameIsCaseInsensitive() {
        failTimes("Budi", "1.1.1.1", 3);
        limiter.recordFailure("BUDI", "1.1.1.1");
        assertTrue(limiter.recordFailure("budi", "1.1.1.1"), "Besar/kecil huruf harus dihitung sebagai akun yang sama");
    }

    @Test
    void oldFailuresSlideOutOfWindow() {
        failTimes("budi", "1.1.1.1", 4);
        clock.advanceSeconds(301);

        for (int i = 0; i < 4; i++) {
            assertFalse(limiter.recordFailure("budi", "1.1.1.1"));   // empat kegagalan lama sudah tidak dihitung
        }
        assertDoesNotThrow(() -> limiter.checkAllowed("budi", "1.1.1.1"));
    }
}
