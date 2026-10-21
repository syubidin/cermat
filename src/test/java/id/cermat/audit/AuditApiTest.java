package id.cermat.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
class AuditApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accounts;

    TestUsers t;

    @BeforeEach
    void setUp() { t = new TestUsers(users, jwt, accounts); }

    private String uid() { return UUID.randomUUID().toString().substring(0, 10); }

    private int countAudit(String action, String actorId) {
        return jdbc.queryForObject(
                "select count(*) from audit_logs where action = ? and actor_id = cast(? as uuid)",
                Integer.class, action, actorId);
    }

    private void deposit(TestUser operator, UUID account, String amount) throws Exception {
        mvc.perform(post("/api/deposits").header("Authorization", operator.bearer())
                        .header("Idempotency-Key", "dep-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + account + "\",\"amount\":" + amount + "}"))
                .andExpect(status().isCreated());
    }

    @Test
    void depositAndTransfer_areAudited_withCorrectActor() throws Exception {
        TestUser op = t.create(Role.OPERATOR);
        TestUser alice = t.create(Role.NASABAH);
        UUID accA = t.openAccount(alice);
        UUID accB = t.openAccount(t.create(Role.NASABAH));

        deposit(op, accA, "50000");

        String res = mvc.perform(post("/api/transfers").header("Authorization", alice.bearer())
                        .header("Idempotency-Key", "trf-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10000}", accA, accB)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String txId = json.readTree(res).get("id").asText();

        Integer transferRows = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'TRANSFER' and entity_id = ? "
                        + "and actor_id = cast(? as uuid)", Integer.class, txId, alice.id().toString());
        assertEquals(1, transferRows, "Transfer harus tercatat atas nama pelakunya");
        assertEquals(1, countAudit("DEPOSIT", op.id().toString()), "Setoran harus tercatat atas nama operator");
    }

    @Test
    void rejectedTransfer_isAudited_evenThoughBusinessTransactionRolledBack() throws Exception {
        TestUser alice = t.create(Role.NASABAH);
        UUID accA = t.openAccount(alice);
        UUID accB = t.openAccount(t.create(Role.NASABAH));

        mvc.perform(post("/api/transfers").header("Authorization", alice.bearer())
                        .header("Idempotency-Key", "trf-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":999999}", accA, accB)))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(1, countAudit("REJECTED", alice.id().toString()));
    }

    @Test
    void accessDenied_isAudited() throws Exception {
        TestUser alice = t.create(Role.NASABAH);
        UUID acc = t.openAccount(alice);

        mvc.perform(post("/api/deposits").header("Authorization", alice.bearer())
                        .header("Idempotency-Key", "dep-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + acc + "\",\"amount\":1000}"))
                .andExpect(status().isForbidden());

        assertEquals(1, countAudit("ACCESS_DENIED", alice.id().toString()));
    }

    @Test
    void failedLogin_isAudited() throws Exception {
        String name = "ghost-" + uid();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"salah-salah\"}"))
                .andExpect(status().isUnauthorized());

        Integer n = jdbc.queryForObject(
                "select count(*) from audit_logs where action = 'LOGIN_FAILED' and entity_id = ?",
                Integer.class, name);
        assertEquals(1, n);
    }

    @Test
    void onlyAuditorCanReadAuditLogs() throws Exception {
        TestUser auditor = t.create(Role.AUDITOR);
        TestUser operator = t.create(Role.OPERATOR);
        TestUser nasabah = t.create(Role.NASABAH);

        mvc.perform(get("/api/audit-logs").header("Authorization", nasabah.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs").header("Authorization", operator.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit-logs").param("size", "5").header("Authorization", auditor.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void auditLog_isAppendOnly() {
        t.create(Role.NASABAH);   // memastikan minimal ada satu baris audit

        assertThrows(DataAccessException.class, () -> jdbc.update("update audit_logs set action = 'X'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from audit_logs"));
    }
}
