package id.cermat.rule;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

/** Nominal melebihi batas. Params: {"max_amount": 100000000} */
@Component
public class HighAmountRule implements Rule {

    @Override public String code() { return "HIGH_AMOUNT"; }

    @Override
    public Optional<String> evaluate(RuleContext ctx, JsonNode params) {
        BigDecimal max = Params.positiveDecimal(params, "max_amount");
        if (ctx.amount().compareTo(max) > 0) {
            return Optional.of("Nominal Rp" + ctx.amount().toPlainString()
                    + " melebihi batas Rp" + max.toPlainString());
        }
        return Optional.empty();
    }

    @Override
    public void validateParams(JsonNode params) {
        Params.positiveDecimal(params, "max_amount");
    }
}
