package id.cermat.user;

import java.util.Map;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.audit.AuditService;
import id.cermat.common.ConflictException;

@Service
public class UserService {

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(UserRepository repo, PasswordEncoder encoder, AuditService audit) {
        this.repo = repo;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional
    public User register(String username, String rawPassword, Role role) {
        if (repo.existsByUsername(username)) {
            throw new ConflictException("Username sudah dipakai: " + username);
        }
        User saved = repo.save(new User(username, encoder.encode(rawPassword), role));
        audit.record("USER_CREATED", "user", saved.getId().toString(),
                Map.of("username", username, "role", role.name()));
        return saved;
    }
}
