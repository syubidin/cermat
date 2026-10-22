package id.cermat.recon;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import id.cermat.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/api/recon")
public class ReconController {

    private final ReconService service;

    public ReconController(ReconService service) { this.service = service; }

    @Operation(summary = "Unggah CSV mitra (kolom: reference_no, amount) dan jalankan rekonsiliasi (khusus OPERATOR)")
    @PreAuthorize("hasRole('OPERATOR')")
    @PostMapping(path = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ReconRepository.BatchView upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam("partnerName") String partnerName,
            @RequestParam("partnerAccountId") UUID partnerAccountId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.reconcile(AuthUser.from(jwt).id(), file, partnerName, partnerAccountId, from, to);
    }

    @Operation(summary = "Daftar batch rekonsiliasi (terbaru di atas)")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR')")
    @GetMapping("/batches")
    public List<ReconRepository.BatchView> list(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listBatches(page, size);
    }

    @Operation(summary = "Ringkasan satu batch")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR')")
    @GetMapping("/batches/{id}")
    public ReconRepository.BatchView get(@PathVariable UUID id) {
        return service.getBatch(id);
    }

    @Operation(summary = "Rincian temuan (filter result: MATCH, AMOUNT_DIFF, MISSING_INTERNAL, MISSING_PARTNER, DUPLICATE_PARTNER)")
    @PreAuthorize("hasAnyRole('OPERATOR','AUDITOR')")
    @GetMapping("/batches/{id}/items")
    public List<ReconRepository.ItemView> items(@PathVariable UUID id,
                                                @RequestParam(required = false) String result,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "100") int size) {
        return service.listItems(id, result, page, size);
    }
}
