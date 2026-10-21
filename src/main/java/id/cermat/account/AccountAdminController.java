package id.cermat.account;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/accounts/{id}")
@PreAuthorize("hasRole('OPERATOR')")
public class AccountAdminController {

    public record FreezeRequest(@Size(max = 200) String reason) {}

    private final AccountAdminService service;

    public AccountAdminController(AccountAdminService service) { this.service = service; }

    @Operation(summary = "Bekukan rekening (khusus OPERATOR)")
    @PostMapping("/freeze")
    public AccountController.AccountResponse freeze(@PathVariable UUID id,
                                                    @Valid @RequestBody(required = false) FreezeRequest req) {
        String reason = (req == null || req.reason() == null || req.reason().isBlank())
                ? "Dibekukan manual oleh operator" : req.reason();
        return AccountController.AccountResponse.from(service.freeze(id, reason));
    }

    @Operation(summary = "Buka kembali rekening yang dibekukan (khusus OPERATOR)")
    @PostMapping("/unfreeze")
    public AccountController.AccountResponse unfreeze(@PathVariable UUID id) {
        return AccountController.AccountResponse.from(service.unfreeze(id));
    }
}
