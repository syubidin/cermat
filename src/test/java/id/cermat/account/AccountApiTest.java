package id.cermat.account;

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

import id.cermat.security.JwtService;
import id.cermat.support.TestUsers;
import id.cermat.support.TestUsers.TestUser;
import id.cermat.user.Role;
import id.cermat.user.UserService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired JwtService jwt;
    @Autowired AccountService accounts;

    TestUsers t;

    @BeforeEach
    void setUp() { t = new TestUsers(users, jwt, accounts); }

    @Test
    void nasabah_opensOwnAccount_withZeroBalance() throws Exception {
        TestUser me = t.create(Role.NASABAH);

        String res = mvc.perform(post("/api/accounts").header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.ownerId").value(me.id().toString()))
                .andReturn().getResponse().getContentAsString();

        String accountId = json.readTree(res).get("id").asText();
        mvc.perform(get("/api/accounts/" + accountId).header("Authorization", me.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void nasabah_cannotOpenAccountForSomeoneElse_returns403() throws Exception {
        TestUser me = t.create(Role.NASABAH);
        TestUser other = t.create(Role.NASABAH);

        mvc.perform(post("/api/accounts").header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + other.id() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operator_opensAccountForCustomer_ownerIdRequired() throws Exception {
        TestUser op = t.create(Role.OPERATOR);
        TestUser customer = t.create(Role.NASABAH);

        mvc.perform(post("/api/accounts").header("Authorization", op.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + customer.id() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(customer.id().toString()));

        mvc.perform(post("/api/accounts").header("Authorization", op.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operator_unknownOwner_returns404() throws Exception {
        TestUser op = t.create(Role.OPERATOR);
        mvc.perform(post("/api/accounts").header("Authorization", op.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }
}
