package id.cermat.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pencatat audit. Pelaku (actor) dan IP diambil otomatis dari request yang sedang berjalan.
 *
 * record()            -> ikut transaksi yang sedang berjalan: atomik dengan operasi bisnis
 *                        (transfer batal => jejak "TRANSFER" ikut batal; tidak ada jejak palsu).
 * recordIndependent() -> transaksi sendiri: tetap tersimpan walau operasi bisnis di-rollback
 *                        (untuk penolakan, login gagal, akses ditolak).
 *
 * JANGAN PERNAH memasukkan password/token ke parameter detail.
 */
@Service
public class AuditService {

    public record AuditLogView(long id, UUID actorId, String action, String entity, String entityId,
                               JsonNode detail, String ipAddress, Instant createdAt) {}

    private static final String INSERT = """
            insert into audit_logs (actor_id, action, entity, entity_id, detail, ip_address)
            values (cast(? as uuid), ?, ?, ?, cast(? as jsonb), ?)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate requiresNew;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String action, String entity, String entityId, Map<String, Object> detail) {
        insert(currentActor(), action, entity, entityId, detail);
    }

    /** actorOverride boleh null: dipakai pelaku dari request yang sedang berjalan (jika ada). */
    public void recordIndependent(UUID actorOverride, String action, String entity,
                                  String entityId, Map<String, Object> detail) {
        UUID actor = actorOverride != null ? actorOverride : currentActor();
        requiresNew.executeWithoutResult(status -> insert(actor, action, entity, entityId, detail));
    }

    public List<AuditLogView> query(int page, int size, String action) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        long offset = (long) Math.max(page, 0) * safeSize;
        String base = "select id, actor_id, action, entity, entity_id, detail::text as detail, "
                + "ip_address, created_at from audit_logs";
        RowMapper<AuditLogView> mapperFn = (rs, i) -> new AuditLogView(
                rs.getLong("id"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("action"),
                rs.getString("entity"),
                rs.getString("entity_id"),
                parse(rs.getString("detail")),
                rs.getString("ip_address"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
        if (action == null || action.isBlank()) {
            return jdbc.query(base + " order by id desc limit ? offset ?", mapperFn, safeSize, offset);
        }
        return jdbc.query(base + " where action = ? order by id desc limit ? offset ?",
                mapperFn, action, safeSize, offset);
    }

    // ---------- internal ----------
    private void insert(UUID actor, String action, String entity, String entityId, Map<String, Object> detail) {
        String json;
        try {
            json = mapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        jdbc.update(INSERT,
                actor == null ? null : actor.toString(),
                trim(action, 50), trim(entity, 50), trim(entityId, 60),
                json, trim(currentIp(), 45));
    }

    private JsonNode parse(String json) {
        try {
            return json == null ? null : mapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static UUID currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            try {
                return UUID.fromString(token.getToken().getSubject());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();   // sengaja bukan X-Forwarded-For (mudah dipalsukan)
        }
        return null;
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
