package id.cermat.user;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** SEMENTARA: dipakai untuk membuat data dummy. Diganti login JWT di Minggu 4. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            Role role) {}

    public record UserResponse(UUID id, String username, Role role) {}

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @Operation(summary = "Daftarkan user dummy (sementara, sebelum JWT)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        Role role = req.role() == null ? Role.NASABAH : req.role();
        User u = service.register(req.username(), req.password(), role);
        return new UserResponse(u.getId(), u.getUsername(), u.getRole());
    }
}
