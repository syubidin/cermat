package id.cermat.account;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    public record OpenAccountRequest(@NotNull UUID ownerId) {}

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
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse open(@Valid @RequestBody OpenAccountRequest req) {
        return AccountResponse.from(service.open(req.ownerId()));
    }

    @Operation(summary = "Lihat detail rekening dan saldo")
    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(service.get(id));
    }

    @Operation(summary = "Daftar rekening milik satu user")
    @GetMapping
    public List<AccountResponse> list(@RequestParam UUID ownerId) {
        return service.listByOwner(ownerId).stream().map(AccountResponse::from).toList();
    }
}
