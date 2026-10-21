package id.cermat.rule;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import id.cermat.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/fraud")
public class FraudController {

    public record ReviewRequest(@NotNull FraudReviewService.Decision decision, @Size(max = 300) String note) {}

    public record ReviewResponse(UUID transactionId, FraudReviewService.Decision decision, UUID reviewedBy) {}

    private final FraudFlagRepository flags;
    private final FraudReviewService review;

    public FraudController(FraudFlagRepository flags, FraudReviewService review) {
        this.flags = flags;
        this.review = review;
    }

    @Operation(summary = "Daftar flag (filter status: PENDING, CLEARED, CONFIRMED)")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR')")
    @GetMapping("/flags")
    public List<FraudFlagRepository.FlagView> flags(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        return flags.list(status, page, size);
    }

    @Operation(summary = "Tinjau transaksi yang ditandai: CLEARED (wajar) atau CONFIRMED (fraud, rekening dibekukan)")
    @PreAuthorize("hasRole('OPERATOR')")
    @PostMapping("/transactions/{transactionId}/review")
    public ReviewResponse review(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID transactionId,
                                 @Valid @RequestBody ReviewRequest req) {
        UUID reviewer = AuthUser.from(jwt).id();
        review.review(transactionId, req.decision(), reviewer, req.note());
        return new ReviewResponse(transactionId, req.decision(), reviewer);
    }
}
