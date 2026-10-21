package id.cermat.rule;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Seed aturan: HIGH_AMOUNT > 100 juta, VELOCITY > 5 transfer/60 detik, NEW_PAYEE_BIG >= 20 juta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FraudRulesApiTest {

    static final String ORIGINAL_HIGH = "{\"params\":{\"max_amount\":100000000},\"enabled\":true}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accountService;

    TestUsers t;
    TestUser operator;
    TestUser auditor;

    record Acct(TestUser owner, String id) {}

    @BeforeEach
    void setUp() {
        t = new TestUsers(users, jwt, accountService);
        operator = t.create(Role.OPERATOR);
        auditor = t.create(Role.AUDITOR);
    }

    // ---------- helper ----------
    private String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    private Acct newAccount(String funding) throws Exception {
        TestUser u = t.create(Role.NASABAH);
        Acct a = new Acct(u, t.openAccount(u).toString());
        if (funding != null) deposit(a, funding);
        return a;
    }

    /** Mengembalikan id transaksi. */
    private String deposit(Acct to, String amount) throws Exception {
        String res = mvc.perform(post("/api/deposits")
                        .header("Authorization", operator.bearer())
                        .header("Idempotency-Key", "dep-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + to.id() + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private ResultActions transferRaw(Acct from, Acct to, String amount) throws Exception {
        return mvc.perform(post("/api/transfers")
                .header("Authorization", from.owner().bearer())
                .header("Idempotency-Key", "trf-" + uid())
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                        from.id(), to.id(), amount)));
    }

    /** Mengembalikan id transaksi. */
    private String transferOk(Acct from, Acct to, String amount) throws Exception {
        String res = transferRaw(from, to, amount).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    private List<String> flags(String txId) {
        return jdbc.queryForList("select rule_code from fraud_flags where transaction_id = cast(? as uuid) "
                + "order by rule_code", String.class, txId);
    }

    private String dbStatus(String txId) {
        return jdbc.queryForObject("select status from transactions where id = cast(? as uuid)", String.class, txId);
    }

    private int auditCount(String action, String entityId) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ? and entity_id = ?",
                Integer.class, action, entityId);
    }

    private ResultActions putRule(String code, String body, TestUser who) throws Exception {
        return mvc.perform(put("/api/rules/" + code).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions review(String txId, String decision, TestUser who) throws Exception {
        return mvc.perform(post("/api/fraud/transactions/" + txId + "/review")
                .header("Authorization", who.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"" + decision + "\",\"note\":\"hasil telepon ke nasabah\"}"));
    }

    private String accountStatus(Acct a) throws Exception {
        String res = mvc.perform(get("/api/accounts/" + a.id()).header("Authorization", operator.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("status").asText();
    }

    // ---------- deteksi ----------
    @Test
    void highAmountToNewPayee_isFlagged_butMoneyStillMoves_andStatusHiddenFromClient() throws Exception {
        Acct a = newAccount("200000000"), b = newAccount(null);

        String res = transferRaw(a, b, "150000000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"))   // klien tidak diberi tahu
                .andReturn().getResponse().getContentAsString();
        String txId = json.readTree(res).get("id").asText();

        assertEquals(List.of("HIGH_AMOUNT", "NEW_PAYEE_BIG"), flags(txId));
        assertEquals("FLAGGED", dbStatus(txId));          // di sisi internal tetap tercatat
        assertEquals(1, auditCount("TRANSACTION_FLAGGED", txId));

        // Opsi A: uang sudah berpindah.
        mvc.perform(get("/api/accounts/" + b.id()).header("Authorization", b.owner().bearer()))
                .andExpect(jsonPath("$.balance").value(150000000));
    }

    @Test
    void normalTransfer_isNotFlagged() throws Exception {
        Acct a = newAccount("50000"), b = newAccount(null);
        String txId = transferOk(a, b, "1000");
        assertTrue(flags(txId).isEmpty());
        assertEquals("SUCCESS", dbStatus(txId));
    }

    @Test
    void velocity_flagsTheSixthTransferWithinWindow() throws Exception {
        Acct a = newAccount("100000"), b = newAccount(null);
        List<String> txs = new ArrayList<>();
        for (int i = 0; i < 6; i++) txs.add(transferOk(a, b, "1000"));

        for (int i = 0; i < 5; i++) assertTrue(flags(txs.get(i)).isEmpty(), "transfer ke-" + (i + 1));
        assertEquals(List.of("VELOCITY"), flags(txs.get(5)));
    }

    @Test
    void knownPayee_isNotTreatedAsNew() throws Exception {
        Acct a = newAccount("40000000"), b = newAccount(null);
        transferOk(a, b, "1000");                         // pertama: kenalan dulu dengan nominal kecil
        String txId = transferOk(a, b, "30000000");       // 30 juta ke penerima yang sudah dikenal
        assertTrue(flags(txId).isEmpty());
    }

    @Test
    void deposits_areNotMonitored() throws Exception {
        Acct a = newAccount(null);
        String txId = deposit(a, "500000000");            // jauh di atas batas HIGH_AMOUNT
        assertTrue(flags(txId).isEmpty());
        assertEquals("SUCCESS", dbStatus(txId));
    }

    // ---------- aturan bisa diubah tanpa deploy ----------
    @Test
    void operatorChangesRuleParams_withoutRedeploy() throws Exception {
        Acct a = newAccount("100000"), b = newAccount(null);
        try {
            putRule("HIGH_AMOUNT", "{\"params\":{\"max_amount\":1000},\"enabled\":true}", operator)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.params.max_amount").value(1000));

            String txId = transferOk(a, b, "5000");        // 5.000 > batas baru 1.000
            assertEquals(List.of("HIGH_AMOUNT"), flags(txId));

            Integer updates = jdbc.queryForObject(
                    "select count(*) from audit_logs where action = 'RULE_UPDATED' "
                            + "and entity_id = 'HIGH_AMOUNT' and actor_id = cast(? as uuid)",
                    Integer.class, operator.id().toString());
            assertEquals(1, updates, "Perubahan aturan harus tercatat atas nama operator");
        } finally {
            putRule("HIGH_AMOUNT", ORIGINAL_HIGH, operator);
        }
    }

    @Test
    void disabledRule_doesNotFlag() throws Exception {
        Acct a = newAccount("200000000"), b = newAccount(null);
        try {
            putRule("HIGH_AMOUNT", "{\"params\":{\"max_amount\":100000000},\"enabled\":false}", operator)
                    .andExpect(status().isOk());
            String txId = transferOk(a, b, "150000000");
            assertEquals(List.of("NEW_PAYEE_BIG"), flags(txId));   // HIGH_AMOUNT mati, yang lain tetap jalan
        } finally {
            putRule("HIGH_AMOUNT", ORIGINAL_HIGH, operator);
        }
    }

    @Test
    void invalidRuleParams_areRejected() throws Exception {
        putRule("VELOCITY", "{\"params\":{\"max_count\":-1,\"window_seconds\":60},\"enabled\":true}", operator)
                .andExpect(status().isBadRequest());
        putRule("VELOCITY", "{\"params\":{\"window_seconds\":60},\"enabled\":true}", operator)
                .andExpect(status().isBadRequest());
        putRule("TIDAK_ADA", "{\"params\":{},\"enabled\":true}", operator)
                .andExpect(status().isNotFound());
    }

    @Test
    void ruleManagement_respectsRoles() throws Exception {
        TestUser nasabah = t.create(Role.NASABAH);

        putRule("HIGH_AMOUNT", ORIGINAL_HIGH, nasabah).andExpect(status().isForbidden());
        putRule("HIGH_AMOUNT", ORIGINAL_HIGH, auditor).andExpect(status().isForbidden());

        mvc.perform(get("/api/rules").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/rules").header("Authorization", nasabah.bearer()))
                .andExpect(status().isForbidden());
    }

    // ---------- tinjauan operator ----------
    @Test
    void review_cleared_keepsAccountActive_andCannotBeReviewedTwice() throws Exception {
        Acct a = newAccount("200000000"), b = newAccount(null);
        String txId = transferOk(a, b, "150000000");

        review(txId, "CLEARED", operator).andExpect(status().isOk());

        Integer notCleared = jdbc.queryForObject(
                "select count(*) from fraud_flags where transaction_id = cast(? as uuid) "
                        + "and review_status <> 'CLEARED'", Integer.class, txId);
        assertEquals(0, notCleared);
        assertEquals("ACTIVE", accountStatus(a));
        assertEquals(1, auditCount("FLAG_REVIEWED", txId));

        review(txId, "CLEARED", operator).andExpect(status().isConflict());
    }

    @Test
    void review_confirmed_freezesSenderAccount_untilOperatorUnfreezes() throws Exception {
        Acct a = newAccount("200000000"), b = newAccount(null);
        String txId = transferOk(a, b, "150000000");

        review(txId, "CONFIRMED", operator).andExpect(status().isOk());
        assertEquals("FROZEN", accountStatus(a));

        // Rekening beku tidak bisa mengirim uang.
        transferRaw(a, b, "1000").andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/accounts/" + a.id() + "/unfreeze").header("Authorization", operator.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        transferOk(a, b, "1000");
    }

    @Test
    void review_andFlagList_respectRoles_andValidateInput() throws Exception {
        Acct a = newAccount("200000000"), b = newAccount(null);
        String txId = transferOk(a, b, "150000000");
        TestUser nasabah = t.create(Role.NASABAH);

        review(txId, "CLEARED", nasabah).andExpect(status().isForbidden());
        review(txId, "CLEARED", auditor).andExpect(status().isForbidden());
        review(UUID.randomUUID().toString(), "CLEARED", operator).andExpect(status().isNotFound());

        mvc.perform(get("/api/fraud/flags").param("status", "PENDING").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/fraud/flags").param("status", "BOGUS").header("Authorization", operator.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fraud/flags").header("Authorization", nasabah.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualFreeze_isOperatorOnly() throws Exception {
        Acct a = newAccount(null);

        mvc.perform(post("/api/accounts/" + a.id() + "/freeze").header("Authorization", a.owner().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/accounts/" + a.id() + "/freeze").header("Authorization", operator.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"permintaan nasabah\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }
}
