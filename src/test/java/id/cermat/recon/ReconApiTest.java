package id.cermat.recon;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;

import id.cermat.account.AccountService;
import id.cermat.security.JwtService;
import id.cermat.support.TestUsers;
import id.cermat.support.TestUsers.TestUser;
import id.cermat.user.Role;
import id.cermat.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accountService;

    TestUsers t;
    TestUser operator;
    TestUser auditor;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

    record Acct(TestUser owner, String id) {}

    @BeforeEach
    void setUp() {
        t = new TestUsers(users, jwt, accountService);
        operator = t.create(Role.OPERATOR);
        auditor = t.create(Role.AUDITOR);
    }

    // ---------- helper ----------
    private String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    private Acct newAccount() {
        TestUser u = t.create(Role.NASABAH);
        return new Acct(u, t.openAccount(u).toString());
    }

    /** Mengembalikan referenceNo transaksi. */
    private String depositRef(Acct to, String amount) throws Exception {
        String res = mvc.perform(post("/api/deposits")
                        .header("Authorization", operator.bearer())
                        .header("Idempotency-Key", "dep-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + to.id() + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("referenceNo").asText();
    }

    private String transferRef(Acct from, Acct to, String amount) throws Exception {
        String res = mvc.perform(post("/api/transfers")
                        .header("Authorization", from.owner().bearer())
                        .header("Idempotency-Key", "trf-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                                from.id(), to.id(), amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("referenceNo").asText();
    }

    private ResultActions upload(TestUser who, String fileName, byte[] content, String accountId,
                                 LocalDate from, LocalDate to) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/csv", content);
        return mvc.perform(multipart("/api/recon/batches").file(file)
                .param("partnerName", "PT Mitra Contoh")
                .param("partnerAccountId", accountId)
                .param("from", from.toString())
                .param("to", to.toString())
                .header("Authorization", who.bearer()));
    }

    /** Periode 3 hari yang mengapit hari ini, aman terhadap pergantian hari WIB. */
    private ResultActions uploadCsv(String csv, Acct partner) throws Exception {
        return upload(operator, "settlement.csv", csv.getBytes(StandardCharsets.UTF_8),
                partner.id(), today.minusDays(1), today.plusDays(1));
    }

    private String batchId(ResultActions res) throws Exception {
        return json.readTree(res.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    // ---------- test ----------
    @Test
    void allResultTypes_areReportedCorrectly() throws Exception {
        Acct p = newAccount(), c = newAccount();
        String r1 = depositRef(p, "100000");
        String r2 = transferRef(p, c, "5000");
        String r3 = transferRef(p, c, "7000");
        String r4 = transferRef(p, c, "9000");   // sengaja tidak ada di file mitra

        String csv = "reference_no,amount\n"
                + r1.toLowerCase() + ",100000.00\n"      // cocok (huruf kecil dinormalisasi)
                + r2 + ",5000\n"                          // cocok
                + r3 + ",7500\n"                          // nominal berbeda (internal 7000)
                + "TRX-NOTINLEDGER0001,3000\n"            // tidak ada di ledger internal
                + r2 + ",5000\n";                         // baris ganda

        ResultActions res = uploadCsv(csv, p)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.amountDiff").value(1))
                .andExpect(jsonPath("$.missingInternal").value(1))
                .andExpect(jsonPath("$.missingPartner").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
        String id = batchId(res);

        mvc.perform(get("/api/recon/batches/" + id + "/items").param("result", "AMOUNT_DIFF")
                        .header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceNo").value(r3))
                .andExpect(jsonPath("$[0].partnerAmount").value(7500))
                .andExpect(jsonPath("$[0].internalAmount").value(7000))
                .andExpect(jsonPath("$[0].difference").value(500));

        mvc.perform(get("/api/recon/batches/" + id + "/items").param("result", "MISSING_PARTNER")
                        .header("Authorization", auditor.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceNo").value(r4))
                .andExpect(jsonPath("$[0].internalAmount").value(9000));

        mvc.perform(get("/api/recon/batches/" + id + "/items").param("result", "MISSING_INTERNAL")
                        .header("Authorization", auditor.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].referenceNo").value("TRX-NOTINLEDGER0001"));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'RECON_COMPLETED' and entity_id = ?",
                Integer.class, id), "Rekonsiliasi harus tercatat di audit log");
    }

    @Test
    void perfectMatch_hasNoExceptions() throws Exception {
        Acct p = newAccount(), c = newAccount();
        String r1 = depositRef(p, "50000");
        String r2 = transferRef(p, c, "1234.50");

        uploadCsv("reference_no,amount\n" + r1 + ",50000\n" + r2 + ",1234.50\n", p)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matched").value(2))
                .andExpect(jsonPath("$.amountDiff").value(0))
                .andExpect(jsonPath("$.missingInternal").value(0))
                .andExpect(jsonPath("$.missingPartner").value(0))
                .andExpect(jsonPath("$.duplicates").value(0));
    }

    @Test
    void sameFileTwice_isRejectedWith409() throws Exception {
        Acct p = newAccount();
        String r1 = depositRef(p, "10000");
        String csv = "reference_no,amount\n" + r1 + ",10000\n";

        uploadCsv(csv, p).andExpect(status().isCreated());
        uploadCsv(csv, p).andExpect(status().isConflict());
    }

    @Test
    void invalidCsvContent_isRejectedWith400() throws Exception {
        Acct p = newAccount();
        String[] badFiles = {
                "reference_no,value\nTRX-A,100\n",                  // kolom amount tidak ada
                "reference_no,amount\nTRX-A,12.345\n",              // lebih dari 2 desimal
                "reference_no,amount\nTRX-A,-5\n",                  // negatif
                "reference_no,amount\nTRX-A,1e3\n",                 // notasi ilmiah
                "reference_no,amount\nTRX-A,0\n",                   // nol
                "reference_no,amount\nTRX-A,1000,extra\n",          // kolom berlebih
                "reference_no,amount\n=CMD|'/C calc'!A0,1000\n",    // percobaan CSV/formula injection
                "reference_no,amount\n@SUM(A1),1000\n",             // awalan @
                "reference_no,amount\n",                            // hanya header
        };
        for (String csv : badFiles) {
            uploadCsv(csv, p).andExpect(status().isBadRequest());
        }

        // File kosong dan ekstensi salah.
        upload(operator, "settlement.csv", new byte[0], p.id(), today, today).andExpect(status().isBadRequest());
        upload(operator, "settlement.txt", "reference_no,amount\nTRX-A,100\n".getBytes(StandardCharsets.UTF_8),
                p.id(), today, today).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedFile_isRejectedWith413() throws Exception {
        Acct p = newAccount();
        byte[] big = new byte[(int) ReconService.MAX_BYTES + 1];
        Arrays.fill(big, (byte) 'a');
        upload(operator, "big.csv", big, p.id(), today, today).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void invalidPeriod_isRejectedWith400() throws Exception {
        Acct p = newAccount();
        byte[] csv = "reference_no,amount\nTRX-A,100\n".getBytes(StandardCharsets.UTF_8);

        upload(operator, "a.csv", csv, p.id(), today, today.minusDays(1)).andExpect(status().isBadRequest());
        upload(operator, "a.csv", csv, p.id(), today.minusDays(40), today).andExpect(status().isBadRequest());
    }

    @Test
    void unknownPartnerAccount_returns404() throws Exception {
        byte[] csv = "reference_no,amount\nTRX-A,100\n".getBytes(StandardCharsets.UTF_8);
        upload(operator, "a.csv", csv, UUID.randomUUID().toString(), today, today).andExpect(status().isNotFound());
    }

    @Test
    void roles_areEnforced() throws Exception {
        Acct p = newAccount();
        TestUser nasabah = t.create(Role.NASABAH);
        byte[] csv = ("reference_no,amount\n" + depositRef(p, "1000") + ",1000\n").getBytes(StandardCharsets.UTF_8);

        upload(nasabah, "a.csv", csv, p.id(), today, today).andExpect(status().isForbidden());
        upload(auditor, "a.csv", csv, p.id(), today, today).andExpect(status().isForbidden());

        String id = batchId(upload(operator, "a.csv", csv, p.id(), today.minusDays(1), today.plusDays(1))
                .andExpect(status().isCreated()));

        mvc.perform(get("/api/recon/batches/" + id).header("Authorization", nasabah.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/recon/batches/" + id).header("Authorization", auditor.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void listAndGet_work_andUnknownBatchIs404() throws Exception {
        Acct p = newAccount();
        String id = batchId(uploadCsv("reference_no,amount\n" + depositRef(p, "2000") + ",2000\n", p)
                .andExpect(status().isCreated()));

        mvc.perform(get("/api/recon/batches").param("size", "1").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(get("/api/recon/batches/" + UUID.randomUUID()).header("Authorization", auditor.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/recon/batches/" + id + "/items").param("result", "BOGUS")
                        .header("Authorization", auditor.bearer()))
                .andExpect(status().isBadRequest());
    }
}
