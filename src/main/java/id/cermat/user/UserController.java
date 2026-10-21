package id.cermat.user;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pembuatan user dengan role tertentu: khusus OPERATOR. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 50) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            Role role) {}

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @Operation(summary = "Buat user dengan role tertentu (khusus OPERATOR)")
    @PreAuthorize("hasRole('OPERATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest req) {
        Role role = req.role() == null ? Role.NASABAH : req.role();
        return UserResponse.from(service.register(req.username(), req.password(), role));
    }
}
