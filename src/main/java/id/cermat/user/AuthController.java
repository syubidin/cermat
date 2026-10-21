package id.cermat.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // Sengaja TIDAK ada field role: pendaftaran mandiri selalu NASABAH (mencegah mass assignment).
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record LoginRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Size(max = 72) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}

    private final UserService users;
    private final AuthService auth;

    public AuthController(UserService users, AuthService auth) {
        this.users = users;
        this.auth = auth;
    }

    @Operation(summary = "Daftar sebagai nasabah")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return UserResponse.from(users.register(req.username(), req.password(), Role.NASABAH));
    }

    @Operation(summary = "Login, mendapatkan JWT")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        var token = auth.login(req.username(), req.password());
        return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
    }
}
