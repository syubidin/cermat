package id.cermat.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import id.cermat.account.AccountService;
import id.cermat.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class TransferController {

    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 200) String description) {}

    public record DepositRequest(
            @NotNull UUID accountId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 200) String description) {}

    public record TransferResponse(UUID id, String referenceNo, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, TxStatus status, Instant createdAt) {
        static TransferResponse from(LedgerTransaction t) {
            return new TransferResponse(t.getId(), t.getReferenceNo(), t.getFromAccountId(),
                    t.getToAccountId(), t.getAmount(), publicStatus(t.getStatus()), t.getCreatedAt());
        }

        /** FLAGGED tidak diperlihatkan ke klien: memberi tahu pelaku bahwa ia terdeteksi justru merugikan. */
        private static TxStatus publicStatus(TxStatus s) {
            return s == TxStatus.FLAGGED ? TxStatus.SUCCESS : s;
        }
    }

    private final TransferService service;
    private final AccountService accounts;

    public TransferController(TransferService service, AccountService accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    @Operation(summary = "Transfer dari rekening milik sendiri (khusus NASABAH)",
            description = "Wajib header Idempotency-Key. Request ulang dengan key dan isi yang sama "
                    + "mengembalikan respons yang sama (header Idempotent-Replayed: true) tanpa memproses ulang.")
    @PreAuthorize("hasRole('NASABAH')")
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader("Idempotency-Key") String idemKey,
                                                     @Valid @RequestBody TransferRequest req) {
        // Rekening asal harus milik pemanggil (selain itu 404, tidak membocorkan keberadaannya).
        accounts.getAuthorized(req.fromAccountId(), AuthUser.from(jwt));
        return respond(service.transfer(
                idemKey, req.fromAccountId(), req.toAccountId(), req.amount(), req.description()));
    }

    @Operation(summary = "Setoran dari kas sistem (khusus OPERATOR)")
    @PreAuthorize("hasRole('OPERATOR')")
    @PostMapping("/deposits")
    public ResponseEntity<TransferResponse> deposit(@RequestHeader("Idempotency-Key") String idemKey,
                                                    @Valid @RequestBody DepositRequest req) {
        return respond(service.deposit(idemKey, req.accountId(), req.amount(), req.description()));
    }

    private ResponseEntity<TransferResponse> respond(TransferResult result) {
        var builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) builder.header(REPLAY_HEADER, "true");
        return builder.body(TransferResponse.from(result.transaction()));
    }
}
