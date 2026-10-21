package id.cermat.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.util.UUID;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    // ---------- helper ----------
    private String uid() { return UUID.randomUUID().toString().substring(0, 8); }

    private String newAccount() throws Exception {
        String user = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"u-" + uid() + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        String ownerId = json.readTree(user).get("id").asText();
        String acc = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + ownerId + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(acc).get("id").asText();
    }

    private ResultActions deposit(String accountId, String amount) throws Exception {
        return mvc.perform(post("/api/deposits").header("Idempotency-Key", "dep-" + uid())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + accountId + "\",\"amount\":" + amount + "}"));
    }

    private ResultActions transfer(String from, String to, String amount, String key) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s}",
                        from, to, amount)));
    }

    private BigDecimal balance(String accountId) throws Exception {
        String res = mvc.perform(get("/api/accounts/" + accountId))
                .andReturn().getResponse().getContentAsString();
        return new BigDecimal(json.readTree(res).get("balance").asText());
    }

    private void assertBalance(String accountId, String expected) throws Exception {
        assertEquals(0, new BigDecimal(expected).compareTo(balance(accountId)),
                "Saldo " + accountId + " seharusnya " + expected + " tetapi " + balance(accountId));
    }

    // ---------- test ----------
    @Test
    void depositThenTransfer_updatesBalancesAndLedger() throws Exception {
        String a = newAccount(), b = newAccount();

        deposit(a, "500000").andExpect(status().isCreated());
        transfer(a, b, "200000", "trf-" + uid())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertBalance(a, "300000");
        assertBalance(b, "200000");

        // a punya 2 entri: kredit (setoran) dan debit (transfer)
        mvc.perform(get("/api/accounts/" + a + "/mutations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].direction").value("DEBIT"));
    }

    @Test
    void transfer_insufficientFunds_returns422_andBalancesUnchanged() throws Exception {
        String a = newAccount(), b = newAccount();
        deposit(a, "1000").andExpect(status().isCreated());

        transfer(a, b, "5000", "trf-" + uid()).andExpect(status().isUnprocessableEntity());

        assertBalance(a, "1000");
        assertBalance(b, "0");
    }

    @Test
    void transfer_sameAccount_returns422() throws Exception {
        String a = newAccount();
        transfer(a, a, "1000", "trf-" + uid()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transfer_missingIdempotencyKey_returns400() throws Exception {
        String a = newAccount(), b = newAccount();
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":1000}", a, b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void transfer_nonPositiveAmount_returns400() throws Exception {
        String a = newAccount(), b = newAccount();
        transfer(a, b, "0", "trf-" + uid()).andExpect(status().isBadRequest());
        transfer(a, b, "-5", "trf-" + uid()).andExpect(status().isBadRequest());
    }

    @Test
    void transfer_duplicateKey_isRejected_andNotAppliedTwice() throws Exception {
        String a = newAccount(), b = newAccount();
        deposit(a, "10000").andExpect(status().isCreated());
        String key = "trf-" + uid();

        transfer(a, b, "1000", key).andExpect(status().isCreated());
        transfer(a, b, "1000", key).andExpect(status().isConflict());

        assertBalance(a, "9000");
        assertBalance(b, "1000");
    }

    @Test
    void globalInvariants_hold() throws Exception {
        String a = newAccount(), b = newAccount();
        deposit(a, "750000").andExpect(status().isCreated());
        transfer(a, b, "123456.78", "trf-" + uid()).andExpect(status().isCreated());

        // 1) Total debit == total kredit di seluruh ledger.
        BigDecimal debit = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from ledger_entries where direction='DEBIT'", BigDecimal.class);
        BigDecimal credit = jdbc.queryForObject(
                "select coalesce(sum(amount),0) from ledger_entries where direction='CREDIT'", BigDecimal.class);
        assertEquals(0, debit.compareTo(credit), "Debit dan kredit harus seimbang");

        // 2) Uang tidak tercipta/hilang: jumlah semua saldo (termasuk kas sistem) selalu 0.
        BigDecimal total = jdbc.queryForObject("select coalesce(sum(balance),0) from accounts", BigDecimal.class);
        assertEquals(0, BigDecimal.ZERO.compareTo(total), "Total seluruh saldo harus 0");

        // 3) Saldo tiap rekening == hasil penjumlahan ledger-nya.
        Integer mismatch = jdbc.queryForObject("""
                select count(*) from accounts a
                where a.balance <> coalesce((select sum(case when l.direction='CREDIT' then l.amount else -l.amount end)
                                              from ledger_entries l where l.account_id = a.id), 0)
                """, Integer.class);
        assertEquals(0, mismatch, "Saldo harus sama dengan hasil replay ledger");
    }

    @Test
    void ledger_isAppendOnly() throws Exception {
        String a = newAccount();
        deposit(a, "1000").andExpect(status().isCreated());

        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_entries set amount = 1"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_entries"));
    }
}
