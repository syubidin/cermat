package id.cermat.recon;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import id.cermat.account.AccountRepository;
import id.cermat.audit.AuditService;
import id.cermat.common.ConflictException;
import id.cermat.common.NotFoundException;

@Service
public class ReconService {

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    static final int MAX_ROWS = 20_000;
    static final int MAX_PERIOD_DAYS = 31;
    /** Batas hari mengikuti WIB, bukan UTC. */
    static final ZoneId ZONE = ZoneId.of("Asia/Jakarta");

    // Whitelist ketat: nilai dari file eksternal tidak dipercaya. Ini juga menolak awalan = + - @ (CSV injection).
    private static final Pattern REFERENCE = Pattern.compile("[A-Z0-9_-]{1,40}");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,17}(\\.\\d{1,2})?");

    record PartnerRow(int row, String reference, BigDecimal amount) {}

    private final ReconRepository repo;
    private final AccountRepository accounts;
    private final AuditService audit;

    public ReconService(ReconRepository repo, AccountRepository accounts, AuditService audit) {
        this.repo = repo;
        this.accounts = accounts;
        this.audit = audit;
    }

    /**
     * Membandingkan file mitra dengan ledger internal untuk satu rekening mitra dan satu periode.
     * Seluruh proses (batch, item, audit) dalam satu transaksi: gagal di tengah = tidak ada yang tersimpan.
     */
    @Transactional
    public ReconRepository.BatchView reconcile(UUID uploaderId, MultipartFile file, String partnerName,
                                               UUID partnerAccountId, LocalDate from, LocalDate to) {
        // ---- 1) Validasi input (semua dianggap tidak tepercaya) ----
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File CSV wajib diunggah dan tidak boleh kosong");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new MaxUploadSizeExceededException(MAX_BYTES);
        }
        if (partnerName == null || partnerName.isBlank() || partnerName.length() > 50) {
            throw new IllegalArgumentException("partnerName wajib diisi (maks 50 karakter)");
        }
        if (partnerAccountId == null || from == null || to == null) {
            throw new IllegalArgumentException("partnerAccountId, from, dan to wajib diisi");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Tanggal akhir tidak boleh sebelum tanggal awal");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("Periode maksimal " + MAX_PERIOD_DAYS + " hari");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("File tidak dapat dibaca");
        }
        if (!accounts.existsById(partnerAccountId)) {
            throw new NotFoundException("Rekening mitra tidak ditemukan: " + partnerAccountId);
        }

        // ---- 2) Cegah file yang sama diproses dua kali ----
        String sha256 = sha256(bytes);
        repo.findBatchIdBySameFile(sha256, partnerAccountId, from, to).ifPresent(existing -> {
            throw new ConflictException("File yang sama sudah direkonsiliasi pada batch " + existing);
        });

        // ---- 3) Baca file mitra dan data internal ----
        List<PartnerRow> partnerRows = parse(bytes);
        Instant start = from.atStartOfDay(ZONE).toInstant();
        Instant endExclusive = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        Map<String, BigDecimal> internal = new HashMap<>();
        for (ReconRepository.InternalTx tx : repo.findInternal(partnerAccountId, start, endExclusive)) {
            internal.put(tx.referenceNo(), tx.amount());
        }

        // ---- 4) Pencocokan ----
        List<ReconRepository.ItemRow> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int matched = 0, amountDiff = 0, missingInternal = 0, missingPartner = 0, duplicates = 0;

        for (PartnerRow row : partnerRows) {
            BigDecimal internalAmount = internal.get(row.reference());
            if (!seen.add(row.reference())) {
                // Kemunculan kedua dan seterusnya: temuan tersendiri (mitra menagih dua kali?).
                items.add(new ReconRepository.ItemRow(row.reference(), row.amount(), internalAmount, "DUPLICATE_PARTNER"));
                duplicates++;
            } else if (internalAmount == null) {
                items.add(new ReconRepository.ItemRow(row.reference(), row.amount(), null, "MISSING_INTERNAL"));
                missingInternal++;
            } else if (internalAmount.compareTo(row.amount()) == 0) {
                items.add(new ReconRepository.ItemRow(row.reference(), row.amount(), internalAmount, "MATCH"));
                matched++;
            } else {
                items.add(new ReconRepository.ItemRow(row.reference(), row.amount(), internalAmount, "AMOUNT_DIFF"));
                amountDiff++;
            }
        }
        for (Map.Entry<String, BigDecimal> e : internal.entrySet()) {
            if (!seen.contains(e.getKey())) {
                items.add(new ReconRepository.ItemRow(e.getKey(), null, e.getValue(), "MISSING_PARTNER"));
                missingPartner++;
            }
        }

        // ---- 5) Simpan + audit ----
        UUID batchId = UUID.randomUUID();
        repo.insertBatch(batchId, partnerName.trim(), fileName, uploaderId, partnerRows.size(), matched,
                missingInternal + missingPartner, amountDiff, duplicates,
                partnerAccountId, from, to, sha256);
        repo.insertItems(batchId, items);
        audit.record("RECON_COMPLETED", "recon_batch", batchId.toString(), Map.of(
                "partnerName", partnerName.trim(), "partnerAccountId", partnerAccountId.toString(),
                "period", from + " s/d " + to, "totalRows", partnerRows.size(), "matched", matched,
                "amountDiff", amountDiff, "missingInternal", missingInternal,
                "missingPartner", missingPartner, "duplicates", duplicates));
        return repo.findBatch(batchId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public ReconRepository.BatchView getBatch(UUID id) {
        return repo.findBatch(id).orElseThrow(() -> new NotFoundException("Batch tidak ditemukan: " + id));
    }

    @Transactional(readOnly = true)
    public List<ReconRepository.BatchView> listBatches(int page, int size) {
        return repo.listBatches(page, size);
    }

    @Transactional(readOnly = true)
    public List<ReconRepository.ItemView> listItems(UUID batchId, String result, int page, int size) {
        getBatch(batchId);   // 404 jika batch tidak ada
        return repo.listItems(batchId, result, page, size);
    }

    // ---------- parsing CSV ----------
    private List<PartnerRow> parse(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);   // buang BOM (Excel "CSV UTF-8")
        }
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setIgnoreHeaderCase(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .build();
        List<PartnerRow> rows = new ArrayList<>();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            Map<String, Integer> header = parser.getHeaderMap();
            if (header == null || !header.containsKey("reference_no") || !header.containsKey("amount")) {
                throw new IllegalArgumentException("Header CSV wajib memuat kolom: reference_no, amount");
            }
            int row = 0;
            for (CSVRecord rec : parser) {
                row++;
                if (row > MAX_ROWS) {
                    throw new IllegalArgumentException("Maksimal " + MAX_ROWS + " baris data per file");
                }
                if (!rec.isConsistent()) {
                    throw new IllegalArgumentException("Baris data ke-" + row + ": jumlah kolom tidak sesuai header");
                }
                String reference = rec.get("reference_no").toUpperCase(Locale.ROOT);
                String amountText = rec.get("amount");
                if (!REFERENCE.matcher(reference).matches()) {
                    throw new IllegalArgumentException("Baris data ke-" + row + ": reference_no tidak valid");
                }
                if (!AMOUNT.matcher(amountText).matches()) {
                    throw new IllegalArgumentException("Baris data ke-" + row
                            + ": amount harus angka positif dengan maksimal 2 desimal (tanpa pemisah ribuan)");
                }
                BigDecimal amount = new BigDecimal(amountText).setScale(2);
                if (amount.signum() <= 0) {
                    throw new IllegalArgumentException("Baris data ke-" + row + ": amount harus lebih dari 0");
                }
                rows.add(new PartnerRow(row, reference, amount));
            }
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("File CSV tidak dapat dibaca");
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("File CSV tidak berisi baris data");
        }
        return rows;
    }

    private static String safeFileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.length() > 200) name = name.substring(name.length() - 200);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("File harus berekstensi .csv");
        }
        return name;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
