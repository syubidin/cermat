package id.cermat.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import id.cermat.account.AccountService;
import id.cermat.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/accounts/{accountId}/mutations")
public class LedgerController {

    public record MutationResponse(Long id, UUID transactionId, Direction direction,
                                   BigDecimal amount, BigDecimal balanceAfter, Instant createdAt) {
        static MutationResponse from(LedgerEntry e) {
            return new MutationResponse(e.getId(), e.getTransactionId(), e.getDirection(),
                    e.getAmount(), e.getBalanceAfter(), e.getCreatedAt());
        }
    }

    private final LedgerEntryRepository entries;
    private final AccountService accounts;

    public LedgerController(LedgerEntryRepository entries, AccountService accounts) {
        this.entries = entries;
        this.accounts = accounts;
    }

    @Operation(summary = "Mutasi rekening (terbaru di atas)")
    @GetMapping
    public List<MutationResponse> mutations(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable UUID accountId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        accounts.getAuthorized(accountId, AuthUser.from(jwt));   // 404 jika bukan haknya / tidak ada
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return entries.findByAccountIdOrderByIdDesc(accountId, pageable)
                .map(MutationResponse::from).getContent();
    }
}
