package id.cermat.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import id.cermat.account.AccountService;
import id.cermat.support.TestUsers;
import id.cermat.support.TestUsers.TestUser;
import id.cermat.user.Role;
import id.cermat.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accounts;

    TestUsers t;

    @BeforeEach
    void setUp() { t = new TestUsers(users, jwt, accounts); }

    private String uid() { return UUID.randomUUID().toString().substring(0, 10); }

    private String credentials(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    private String loginToken(String username, String password) throws Exception {
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("accessToken").asText();
    }

    @Test
    void noToken_returns401() throws Exception {
        mvc.perform(get("/api/accounts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerThenLogin_tokenWorks() throws Exception {
        String name = "reg-" + uid();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(name, "password123")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("NASABAH"));

        String token = loginToken(name, "password123");
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void login_wrongPasswordOrUnknownUser_returns401_withSameMessage() throws Exception {
        String name = "lg-" + uid();
        users.register(name, "password123", Role.NASABAH);

        String wrongPassword = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(name, "salah-banget")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("tidak-ada-" + uid(), "password123")))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Pesan sama: penyerang tidak bisa membedakan "user ada" dan "user tidak ada".
        org.junit.jupiter.api.Assertions.assertEquals(
                json.readTree(wrongPassword).get("message").asText(),
                json.readTree(unknownUser).get("message").asText());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        String name = "dup-" + uid();
        String body = credentials(name, "password123");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void register_cannotEscalateRole_viaRequestBody() throws Exception {
        String name = "esc-" + uid();
        // Penyerang mencoba menyisipkan role OPERATOR (mass assignment).
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"password123\",\"role\":\"OPERATOR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("NASABAH"));

        String token = loginToken(name, "password123");
        mvc.perform(post("/api/deposits").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "esc-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"amount\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nasabah_cannotAccessAnotherCustomersAccount_idor() throws Exception {
        TestUser alice = t.create(Role.NASABAH);
        TestUser bob = t.create(Role.NASABAH);
        UUID aliceAccount = t.openAccount(alice);
        UUID bobAccount = t.openAccount(bob);

        // Bob mencoba membaca, melihat mutasi, dan mentransfer dari rekening Alice: semua 404.
        mvc.perform(get("/api/accounts/" + aliceAccount).header("Authorization", bob.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/" + aliceAccount + "/mutations").header("Authorization", bob.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/transfers").header("Authorization", bob.bearer())
                        .header("Idempotency-Key", "idor-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":1000}",
                                aliceAccount, bobAccount)))
                .andExpect(status().isNotFound());

        // Bob juga tidak boleh meminta daftar rekening milik Alice.
        mvc.perform(get("/api/accounts").param("ownerId", alice.id().toString())
                        .header("Authorization", bob.bearer()))
                .andExpect(status().isForbidden());

        // Alice sendiri tentu boleh.
        mvc.perform(get("/api/accounts/" + aliceAccount).header("Authorization", alice.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void nasabah_cannotDeposit_returns403() throws Exception {
        TestUser me = t.create(Role.NASABAH);
        UUID acc = t.openAccount(me);
        mvc.perform(post("/api/deposits").header("Authorization", me.bearer())
                        .header("Idempotency-Key", "dep-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + acc + "\",\"amount\":1000000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditor_canRead_butCannotMoveMoney() throws Exception {
        TestUser auditor = t.create(Role.AUDITOR);
        TestUser customer = t.create(Role.NASABAH);
        UUID acc = t.openAccount(customer);
        UUID other = t.openAccount(t.create(Role.NASABAH));

        mvc.perform(get("/api/accounts/" + acc).header("Authorization", auditor.bearer()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/transfers").header("Authorization", auditor.bearer())
                        .header("Idempotency-Key", "aud-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(
                                "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":1000}", acc, other)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/deposits").header("Authorization", auditor.bearer())
                        .header("Idempotency-Key", "aud-" + uid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + acc + "\",\"amount\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void tamperedToken_returns401() throws Exception {
        TestUser me = t.create(Role.NASABAH);
        String token = me.token();
        String tampered = token.substring(0, token.length() - 3) + "abc";   // rusak tanda tangannya

        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer bukan.token.valid"))
                .andExpect(status().isUnauthorized());
    }
}
