package id.cermat.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import id.cermat.common.BusinessException;

@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final FraudRuleRepository repo;
    private final Map<String, Rule> implementations;

    public RuleEngine(FraudRuleRepository repo, List<Rule> rules) {
        this.repo = repo;
        this.implementations = rules.stream().collect(Collectors.toMap(Rule::code, Function.identity()));
    }

    /** Membaca aturan aktif dari database setiap kali, sehingga perubahan operator berlaku seketika. */
    public List<RuleHit> evaluate(RuleContext ctx) {
        List<RuleHit> hits = new ArrayList<>();
        for (FraudRuleRepository.RuleRow cfg : repo.findEnabled()) {
            Rule impl = implementations.get(cfg.code());
            if (impl == null) {
                log.warn("Aturan '{}' aktif di database tetapi tidak punya implementasi, dilewati", cfg.code());
                continue;
            }
            try {
                impl.evaluate(ctx, cfg.params())
                        .ifPresent(reason -> hits.add(new RuleHit(cfg.code(), reason)));
            } catch (IllegalArgumentException e) {
                // Parameter rusak: arahkan ke tinjauan manusia, jangan diam-diam meloloskan transaksi.
                log.error("Parameter aturan '{}' tidak valid: {}", cfg.code(), e.getMessage());
                hits.add(new RuleHit(cfg.code(), "Aturan tidak dapat dievaluasi (konfigurasi tidak valid)"));
            }
        }
        return hits;
    }

    public void validate(String code, JsonNode params) {
        Rule impl = implementations.get(code);
        if (impl == null) {
            throw new BusinessException("Tidak ada implementasi untuk aturan " + code);
        }
        impl.validateParams(params);
    }
}
