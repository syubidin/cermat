package id.cermat.rule;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import id.cermat.audit.AuditService;
import id.cermat.common.NotFoundException;

@Service
public class FraudRuleService {

    private final FraudRuleRepository repo;
    private final RuleEngine engine;
    private final AuditService audit;

    public FraudRuleService(FraudRuleRepository repo, RuleEngine engine, AuditService audit) {
        this.repo = repo;
        this.engine = engine;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FraudRuleRepository.RuleRow> list() {
        return repo.findAll();
    }

    /** Perubahan aturan selalu tercatat di audit log lengkap dengan nilai sebelum dan sesudahnya. */
    @Transactional
    public FraudRuleRepository.RuleRow update(String code, JsonNode params, boolean enabled) {
        FraudRuleRepository.RuleRow before = repo.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Aturan tidak ditemukan: " + code));
        engine.validate(code, params);
        repo.update(code, params, enabled);
        audit.record("RULE_UPDATED", "fraud_rule", code, Map.of(
                "before", before.params(), "after", params,
                "enabledBefore", before.enabled(), "enabledAfter", enabled));
        return repo.findByCode(code).orElseThrow();
    }
}
