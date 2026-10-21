package id.cermat.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class TransferController {

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
                    t.getToAccountId(), t.getAmount(), t.getStatus(), t.getCreatedAt());
        }
    }

    private final TransferService service;

    public TransferController(TransferService service) { this.service = service; }

    @Operation(summary = "Transfer antar rekening (wajib header Idempotency-Key)")
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@RequestHeader("Idempotency-Key") String idemKey,
                                     @Valid @RequestBody TransferRequest req) {
        return TransferResponse.from(service.transfer(
                idemKey, req.fromAccountId(), req.toAccountId(), req.amount(), req.description()));
    }

    @Operation(summary = "Setoran dummy dari kas sistem (nanti khusus OPERATOR)")
    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse deposit(@RequestHeader("Idempotency-Key") String idemKey,
                                    @Valid @RequestBody DepositRequest req) {
        return TransferResponse.from(service.deposit(
                idemKey, req.accountId(), req.amount(), req.description()));
    }
}
