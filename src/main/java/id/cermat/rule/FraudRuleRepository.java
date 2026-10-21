package id.cermat.rule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Memakai JdbcTemplate (bukan JPA) agar kolom JSONB dibaca/ditulis apa adanya. */
@Repository
public class FraudRuleRepository {

    public record RuleRow(String code, String description, JsonNode params, boolean enabled, Instant updatedAt) {}

    private static final String SELECT =
            "select code, description, params::text as params, enabled, updated_at from fraud_rules";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public FraudRuleRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<RuleRow> findAll() {
        return jdbc.query(SELECT + " order by code", this::map);
    }

    public List<RuleRow> findEnabled() {
        return jdbc.query(SELECT + " where enabled order by code", this::map);
    }

    public Optional<RuleRow> findByCode(String code) {
        return jdbc.query(SELECT + " where code = ?", this::map, code).stream().findFirst();
    }

    public void update(String code, JsonNode params, boolean enabled) {
        jdbc.update("update fraud_rules set params = cast(? as jsonb), enabled = ?, updated_at = now() "
                + "where code = ?", params.toString(), enabled, code);
    }

    private RuleRow map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new RuleRow(rs.getString("code"), rs.getString("description"),
                    mapper.readTree(rs.getString("params")), rs.getBoolean("enabled"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("params tidak valid untuk aturan " + rs.getString("code"), e);
        }
    }
}
