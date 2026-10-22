package id.cermat.recon;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import id.cermat.account.AccountRepository;
import id.cermat.audit.AuditService;
import id.cermat.common.ConflictException;
import id.cermat.common.NotFoundException;

class ReconServiceTest {

    ReconRepository repo = mock(ReconRepository.class);
    AccountRepository accounts = mock(AccountRepository.class);
    AuditService audit = mock(AuditService.class);
    ReconService service;

    UUID uploader = UUID.randomUUID();
    UUID partnerAccount = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 10, 21);
    LocalDate to = LocalDate.of(2026, 10, 22);

    @BeforeEach
    void setUp() {
        service = new ReconService(repo, accounts, audit);
        when(accounts.existsById(partnerAccount)).thenReturn(true);
        when(repo.findBatchIdBySameFile(any(), eq(partnerAccount), any(), any())).thenReturn(Optional.empty());
        when(repo.findInternal(eq(partnerAccount), any(), any())).thenReturn(List.of());
        when(repo.findBatch(any())).thenReturn(Optional.of(new ReconRepository.BatchView(
                UUID.randomUUID(), "Mitra", "x.csv", partnerAccount, from, to,
                0, 0, 0, 0, 0, 0, uploader, Instant.now())));
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "x.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private void reconcile(MockMultipartFile file) {
        service.reconcile(uploader, file, "Mitra", partnerAccount, from, to);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void classifiesEveryResultType_andCountsCorrectly() {
        when(repo.findInternal(eq(partnerAccount), any(), any())).thenReturn(List.of(
                new ReconRepository.InternalTx("TRX-AAA", new BigDecimal("1000.00")),
                new ReconRepository.InternalTx("TRX-BBB", new BigDecimal("2000.00")),
                new ReconRepository.InternalTx("TRX-CCC", new BigDecimal("3000.00"))));

        reconcile(csv("reference_no,amount\nTRX-AAA,1000\nTRX-BBB,2500\nTRX-ZZZ,10\nTRX-AAA,1000\n"));

        ArgumentCaptor<List<ReconRepository.ItemRow>> items = ArgumentCaptor.forClass((Class) List.class);
        verify(repo).insertItems(any(), items.capture());
        Set<String> results = items.getValue().stream()
                .map(i -> i.referenceNo() + ":" + i.result()).collect(Collectors.toSet());
        assertEquals(Set.of("TRX-AAA:MATCH", "TRX-BBB:AMOUNT_DIFF", "TRX-ZZZ:MISSING_INTERNAL",
                "TRX-AAA:DUPLICATE_PARTNER", "TRX-CCC:MISSING_PARTNER"), results);

        // totalRows=4, matched=1, missing=2 (internal+partner), mismatched=1, duplicates=1
        verify(repo).insertBatch(any(), eq("Mitra"), eq("x.csv"), eq(uploader), eq(4), eq(1), eq(2), eq(1), eq(1),
                eq(partnerAccount), eq(from), eq(to), anyString());
    }

    @Test
    void rejectsMaliciousOrMalformedRows() {
        String[] bad = {
                "reference_no,amount\n=CMD|'/C calc'!A0,1000\n",   // formula injection
                "reference_no,amount\n+SUM(1+1),1000\n",
                "reference_no,amount\nTRX-A,12.345\n",             // 3 desimal
                "reference_no,amount\nTRX-A,1e3\n",                // notasi ilmiah
                "reference_no,amount\nTRX-A,-5\n",
                "reference_no,amount\nTRX-A,1000,extra\n",         // kolom berlebih
                "reference_no,value\nTRX-A,1000\n",                // header salah
                "reference_no,amount\n",                           // tanpa data
        };
        for (String content : bad) {
            assertThrows(IllegalArgumentException.class, () -> reconcile(csv(content)), content);
        }
        verify(repo, never()).insertBatch(any(), any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), any(), any(), any(), any());
    }

    @Test
    void guardsAgainstOversizeUnknownAccountAndDuplicateFile() {
        byte[] big = new byte[(int) ReconService.MAX_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        assertThrows(MaxUploadSizeExceededException.class,
                () -> reconcile(new MockMultipartFile("file", "x.csv", "text/csv", big)));

        UUID unknown = UUID.randomUUID();
        assertThrows(NotFoundException.class, () -> service.reconcile(uploader,
                csv("reference_no,amount\nTRX-A,1000\n"), "Mitra", unknown, from, to));

        when(repo.findBatchIdBySameFile(any(), eq(partnerAccount), any(), any()))
                .thenReturn(Optional.of(UUID.randomUUID()));
        assertThrows(ConflictException.class, () -> reconcile(csv("reference_no,amount\nTRX-A,1000\n")));
    }
}
