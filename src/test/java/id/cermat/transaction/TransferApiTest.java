package id.cermat.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
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
class TransferApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accountService;

    TestUsers t;
    TestUser operator;

    record Acct(TestUser owner, String id) {}

    @BeforeEach
    void setUp() {
        t = new TestUsers(users, jwt, accountService);
        operator = t.create(Role.OPERATOR);
    }

    // ---------- helper ----------
    private String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    private Acct newAccount() {
        TestUser u = t.create(Role.NASABAH);
        return new Acct(u, t.openAccount(u).toString());
    }

    private ResultActions deposit(Acct to, String amount) throws Exception {
        return mvc.perform(post("/api/deposits")
                .header("Authorization", operator.bearer())
                .header("Idempotency-Key", "dep-" + uid())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + to.id() + "\",\"amount\":" + amount + "}"));
    }

    private ResultActions transfer(Acct from, Acct to, String amount, String key) throws Exception {
        return mvc.perform(post("/api/transfers")
                .header("Authorization", from.owner().bearer())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                        from.id(), to.id(), amount)));
    }

    private BigDecimal balance(Acct a) throws Exception {
        String res = mvc.perform(get("/api/accounts/" + a.id()).header("Authorization", a.owner().bearer()))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(json.readTree(res).get("balance").asText());
    }

    private void assertBalance(Acct a, String expected) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(balance(a)),
                "Saldo seharusnya " + expected + " tetapi " + balance(a));
    }

    // ---------- test ----------
    @Test
    void depositThenTransfer_updatesBalancesAndLedger() throws Exception {
        Acct a = newAccount(), b = newAccount();

        deposit(a, "500000").andExpect(status().isCreated());
        transfer(a, b, "200000", "trf-" + uid())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertBalance(a, "300000");
        assertBalance(b, "200000");

        mvc.perform(get("/api/accounts/" + a.id() + "/mutations").header("Authorization", a.owner().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"));
    }

    @Test
    void transfer_insufficientFunds_returns422_andBalancesUnchanged() throws Exception {
        Acct a = newAccount(), b = newAccount();
        deposit(a, "1000").andExpect(status().isCreated());

        transfer(a, b, "5000", "trf-" + uid()).andExpect(status().isUnprocessableEntity());

        assertBalance(a, "1000");
        assertBalance(b, "0");
    }

    @Test
    void transfer_sameAccount_returns422() throws Exception {
        Acct a = newAccount();
        transfer(a, a, "1000", "trf-" + uid()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transfer_missingIdempotencyKey_returns400() throws Exception {
        Acct a = newAccount(), b = newAccount();
        mvc.perform(post("/api/transfers").header("Authorization", a.owner().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":1000}", a.id(), b.id())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transfer_nonPositiveAmount_returns400() throws Exception {
        Acct a = newAccount(), b = newAccount();
        transfer(a, b, "0", "trf-" + uid()).andExpect(status().isBadRequest());
        transfer(a, b, "-5", "trf-" + uid()).andExpect(status().isBadRequest());
    }

    @Test
    void transfer_duplicateKey_replaysSameResponse_andAppliesOnce() throws Exception {
        Acct a = newAccount(), b = newAccount();
        deposit(a, "10000").andExpect(status().isCreated());
        String key = "trf-" + uid();

        String first = transfer(a, b, "1000", key)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        String second = transfer(a, b, "1000", key)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(json.readTree(first).get("id").asText(), json.readTree(second).get("id").asText());
        assertBalance(a, "9000");
        assertBalance(b, "1000");
    }

    @Test
    void transfer_sameKeyDifferentPayload_returns422() throws Exception {
        Acct a = newAccount(), b = newAccount();
        deposit(a, "10000").andExpect(status().isCreated());
        String key = "trf-" + uid();

        transfer(a, b, "1000", key).andExpect(status().isCreated());
        transfer(a, b, "2000", key).andExpect(status().isUnprocessableEntity());

        assertBalance(a, "9000");
    }

    @Test
    void globalInvariants_hold() throws Exception {
        Acct a = newAccount(), b = newAccount();
        deposit(a, "750000").andExpect(status().isCreated());
        transfer(a, b, "123456.78", "trf-" + uid()).andExpect(status().isCreated());

        BigDecimal debit = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from ledger_entries where direction='DEBIT'", BigDecimal.class);
        BigDecimal credit = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from ledger_entries where direction='CREDIT'", BigDecimal.class);
        assertEquals(0, debit.compareTo(credit), "Debit dan kredit harus seimbang");

        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance),0) from accounts", BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(total), "Total seluruh saldo harus 0");

        Integer mismatch = jdbc.queryForObject("""
                select count(*) from accounts a
                where a.balance <> coalesce((select sum(case when l.direction='CREDIT' then l.amount else -l.amount end)
                                              from ledger_entries l where l.account_id = a.id), 0)
                """, Integer.class);
        assertEquals(0, mismatch, "Saldo harus sama dengan hasil replay ledger");
    }

    @Test
    void ledger_isAppendOnly() throws Exception {
        Acct a = newAccount();
        deposit(a, "1000").andExpect(status().isCreated());

        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_entries set amount = 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_entries"));
    }
}
