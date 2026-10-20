package id.cermat.user;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import id.cermat.common.ConflictException;

@Service
public class UserService {

    private final UserRepository repo;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserService(UserRepository repo) { this.repo = repo; }

    @Transactional
    public User register(String username, String rawPassword, Role role) {
        if (repo.existsByUsername(username)) {
            throw new ConflictException("Username sudah dipakai: " + username);
        }
        return repo.save(new User(username, encoder.encode(rawPassword), role));
    }
}
