package id.cermat.rule;

import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/rules")
public class RuleController {

    public record RuleResponse(String code, String description, JsonNode params, boolean enabled, Instant updatedAt) {
        static RuleResponse from(FraudRuleRepository.RuleRow r) {
            return new RuleResponse(r.code(), r.description(), r.params(), r.enabled(), r.updatedAt());
        }
    }

    public record UpdateRuleRequest(@NotNull JsonNode params, @NotNull Boolean enabled) {}

    private final FraudRuleService service;

    public RuleController(FraudRuleService service) { this.service = service; }

    @Operation(summary = "Daftar aturan deteksi")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR')")
    @GetMapping
    public List<RuleResponse> list() {
        return service.list().stream().map(RuleResponse::from).toList();
    }

    @Operation(summary = "Ubah parameter / aktifkan-nonaktifkan aturan (khusus OPERATOR, tanpa deploy ulang)")
    @PreAuthorize("hasRole('OPERATOR')")
    @PutMapping("/{code}")
    public RuleResponse update(@PathVariable String code, @Valid @RequestBody UpdateRuleRequest req) {
        return RuleResponse.from(service.update(code, req.params(), req.enabled()));
    }
}
