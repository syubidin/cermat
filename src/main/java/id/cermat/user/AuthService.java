package id.cermat.user;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import id.cermat.audit.AuditService;
import id.cermat.common.UnauthorizedException;
import id.cermat.security.JwtService;

/** Sengaja TIDAK @Transactional: jejak login gagal harus tetap tersimpan walau exception dilempar. */
@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final AuditService audit;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.audit = audit;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    public JwtService.Token login(String username, String password) {
        User user = users.findByUsername(username).orElse(null);
        // Tetap hitung BCrypt walau user tidak ada, agar waktu respons tidak membocorkan keberadaan username.
        boolean ok = user != null
                ? encoder.matches(password, user.getPasswordHash())
                : encoder.matches(password, dummyHash) && false;
        if (!ok) {
            audit.recordIndependent(null, "LOGIN_FAILED", "user", username, Map.of());
            throw new UnauthorizedException("Username atau password salah");   // pesan sama untuk kedua kasus
        }
        audit.recordIndependent(user.getId(), "LOGIN_SUCCESS", "user", user.getId().toString(), Map.of());
        return jwt.issue(user);
    }
}
