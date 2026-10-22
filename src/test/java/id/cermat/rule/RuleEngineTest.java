package id.cermat.rule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import id.cermat.common.BusinessException;

class RuleEngineTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final RuleContext CTX = new RuleContext(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1000"), Instant.now());

    /** Aturan tiruan yang perilakunya bisa kita atur. */
    private Rule rule(String code, Optional<String> result, boolean throwsInvalidParams) {
        Rule r = mock(Rule.class);
        when(r.code()).thenReturn(code);
        if (throwsInvalidParams) {
            when(r.evaluate(any(), any())).thenThrow(new IllegalArgumentException("params rusak"));
        } else {
            when(r.evaluate(any(), any())).thenReturn(result);
        }
        return r;
    }

    private FraudRuleRepository.RuleRow row(String code) {
        JsonNode params = MAPPER.createObjectNode();
        return new FraudRuleRepository.RuleRow(code, "deskripsi", params, true, Instant.now());
    }

    @Test
    void collectsHitsOnlyFromRulesThatTrigger() {
        FraudRuleRepository repo = mock(FraudRuleRepository.class);
        when(repo.findEnabled()).thenReturn(List.of(row("A"), row("B")));
        RuleEngine engine = new RuleEngine(repo, List.of(
                rule("A", Optional.of("alasan A"), false),
                rule("B", Optional.empty(), false)));

        List<RuleHit> hits = engine.evaluate(CTX);

        assertEquals(1, hits.size());
        assertEquals("A", hits.get(0).ruleCode());
        assertEquals("alasan A", hits.get(0).reason());
    }

    @Test
    void brokenRuleConfiguration_isSentToHumanReview_notSilentlyPassed() {
        FraudRuleRepository repo = mock(FraudRuleRepository.class);
        when(repo.findEnabled()).thenReturn(List.of(row("BAD")));
        RuleEngine engine = new RuleEngine(repo, List.of(rule("BAD", Optional.empty(), true)));

        List<RuleHit> hits = engine.evaluate(CTX);

        assertEquals(1, hits.size());
        assertTrue(hits.get(0).reason().contains("tidak dapat dievaluasi"), hits.get(0).reason());
    }

    @Test
    void enabledRuleWithoutImplementation_isSkipped_andNoRulesMeansNoHits() {
        FraudRuleRepository repo = mock(FraudRuleRepository.class);
        when(repo.findEnabled()).thenReturn(List.of(row("TANPA_IMPLEMENTASI")));
        RuleEngine engine = new RuleEngine(repo, List.of());

        assertTrue(engine.evaluate(CTX).isEmpty());

        when(repo.findEnabled()).thenReturn(List.of());
        assertTrue(engine.evaluate(CTX).isEmpty());
    }

    @Test
    void validate_delegatesToRule_andRejectsUnknownCode() {
        Rule rule = rule("A", Optional.empty(), false);
        RuleEngine engine = new RuleEngine(mock(FraudRuleRepository.class), List.of(rule));
        JsonNode params = MAPPER.createObjectNode();

        engine.validate("A", params);
        verify(rule).validateParams(params);

        assertThrows(BusinessException.class, () -> engine.validate("TIDAK_ADA", params));
    }
}
