package id.cermat.audit;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @Operation(summary = "Baca audit log (khusus AUDITOR)")
    @PreAuthorize("hasRole('AUDITOR')")
    @GetMapping
    public List<AuditService.AuditLogView> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size,
                                                @RequestParam(required = false) String action) {
        return audit.query(page, size, action);
    }
}
