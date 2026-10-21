package id.cermat.support;

import java.util.UUID;

import id.cermat.account.AccountService;
import id.cermat.security.JwtService;
import id.cermat.user.Role;
import id.cermat.user.User;
import id.cermat.user.UserService;

/** Helper test: membuat user + token langsung (tanpa lewat endpoint login). */
public class TestUsers {

    public record TestUser(UUID id, String username, String token) {
        public String bearer() { return "Bearer " + token; }
    }

    private final UserService users;
    private final JwtService jwt;
    private final AccountService accounts;

    public TestUsers(UserService users, JwtService jwt, AccountService accounts) {
        this.users = users;
        this.jwt = jwt;
        this.accounts = accounts;
    }

    public TestUser create(Role role) {
        String name = "t-" + UUID.randomUUID().toString().substring(0, 12);
        User u = users.register(name, "password123", role);
        return new TestUser(u.getId(), name, jwt.issue(u).value());
    }

    public UUID openAccount(TestUser owner) {
        return accounts.open(owner.id()).getId();
    }
}
