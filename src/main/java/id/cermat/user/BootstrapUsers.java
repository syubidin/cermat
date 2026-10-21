package id.cermat.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Membuat user 'operator' dan 'auditor' pertama KALI jika password diberikan lewat environment variable.
 * Password tidak pernah ditulis di repo. Jika variabel kosong, tidak melakukan apa-apa.
 */
@Component
public class BootstrapUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapUsers.class);

    private final UserService users;
    private final UserRepository repo;
    private final String operatorPassword;
    private final String auditorPassword;

    public BootstrapUsers(UserService users, UserRepository repo,
                          @Value("${cermat.bootstrap.operator-password:}") String operatorPassword,
                          @Value("${cermat.bootstrap.auditor-password:}") String auditorPassword) {
        this.users = users;
        this.repo = repo;
        this.operatorPassword = operatorPassword;
        this.auditorPassword = auditorPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        create("operator", operatorPassword, Role.OPERATOR);
        create("auditor", auditorPassword, Role.AUDITOR);
    }

    private void create(String username, String password, Role role) {
        if (password == null || password.isBlank() || repo.existsByUsername(username)) return;
        users.register(username, password, role);
        log.info("User bootstrap dibuat: {} ({})", username, role);
    }
}
