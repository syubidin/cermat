package id.cermat.user;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import id.cermat.audit.AuditService;
import id.cermat.common.UnauthorizedException;
import id.cermat.security.JwtService;
import id.cermat.security.LoginRateLimiter;

/** Sengaja TIDAK @Transactional: jejak login gagal harus tetap tersimpan walau exception dilempar. */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final LoginRateLimiter limiter;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
                       AuditService audit, LoginRateLimiter limiter) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.limiter = limiter;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public JwtService.Token login(String username, String password, String ip) {
        // 1) Cek pembatas lebih dulu: murah, dan tidak membuang CPU BCrypt untuk penyerang.
        limiter.checkAllowed(username, ip);

        User user = users.findByUsername(username).orElse(null);
        boolean ok;
        if (user != null) {
            ok = encoder.matches(password, user.getPasswordHash());
        } else {
            encoder.matches(password, dummyHash);   // samakan waktu respons; hasilnya diabaikan
            ok = false;
        }

        if (!ok) {
            boolean lockedNow = limiter.recordFailure(username, ip);
            audit.recordIndependent(null, "LOGIN_FAILED", "user", username, Map.of());
            if (lockedNow) {
                // Hanya dicatat sekali saat kunci terpasang, agar penyerang tidak bisa membanjiri audit log.
                audit.recordIndependent(null, "LOGIN_LOCKED", "user", username,
                        Map.of("ip", String.valueOf(ip)));
            }
            throw new UnauthorizedException("Username atau password salah");   // pesan sama untuk kedua kasus
        }

        limiter.recordSuccess(username, ip);
        audit.recordIndependent(user.getId(), "LOGIN_SUCCESS", "user", user.getId().toString(), Map.of());
        return jwt.issue(user);
    }
}
