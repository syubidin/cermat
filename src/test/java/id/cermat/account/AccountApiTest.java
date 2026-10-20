package id.cermat.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String createUser() throws Exception {
        String body = "{\"username\":\"user-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"password\":\"password123\"}";
        String res = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void openAccount_startsWithZeroBalance() throws Exception {
        String ownerId = createUser();

        String res = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + ownerId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String accountId = json.readTree(res).get("id").asText();
        mvc.perform(get("/api/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId));
    }

    @Test
    void openAccount_unknownOwner_returns404() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        String body = "{\"username\":\"dup-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"password\":\"password123\"}";
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}
