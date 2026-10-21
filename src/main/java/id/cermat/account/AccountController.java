package id.cermat.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import id.cermat.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    /** ownerId opsional: nasabah otomatis memakai identitasnya sendiri; operator wajib mengisi. */
    public record OpenAccountRequest(UUID ownerId) {}

    public record AccountResponse(UUID id, String accountNumber, UUID ownerId,
                                  String currency, BigDecimal balance, AccountStatus status) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.getId(), a.getAccountNumber(), a.getOwnerId(),
                    a.getCurrency(), a.getBalance(), a.getStatus());
        }
    }

    private final AccountService service;

    public AccountController(AccountService service) { this.service = service; }

    @Operation(summary = "Buka rekening baru (saldo awal 0)")
    @PreAuthorize("hasAnyRole('NASABAH','OPERATOR')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@AuthenticationPrincipal Jwt jwt,
                                @RequestBody(required = false) OpenAccountRequest req) {
        UUID requestedOwner = req == null ? null : req.ownerId();
        return AccountResponse.from(service.open(AuthUser.from(jwt), requestedOwner));
    }

    @Operation(summary = "Lihat detail rekening dan saldo")
    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AccountResponse.from(service.getAuthorized(id, AuthUser.from(jwt)));
    }

    @Operation(summary = "Daftar rekening (nasabah: milik sendiri; staf: wajib ownerId)")
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) UUID ownerId) {
        return service.listFor(AuthUser.from(jwt), ownerId).stream().map(AccountResponse::from).toList();
    }
}
