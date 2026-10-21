package id.cermat.rule;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import id.cermat.transaction.LedgerTransactionRepository;

/** Penerima baru (belum pernah dikirimi) dengan nominal besar. Params: {"min_amount": 20000000} */
@Component
public class NewPayeeBigRule implements Rule {

    private final LedgerTransactionRepository transactions;

    public NewPayeeBigRule(LedgerTransactionRepository transactions) { this.transactions = transactions; }

    @Override public String code() { return "NEW_PAYEE_BIG"; }

    @Override
    public Optional<String> evaluate(RuleContext ctx, JsonNode params) {
        BigDecimal min = Params.positiveDecimal(params, "min_amount");
        if (ctx.amount().compareTo(min) >= 0
                && !transactions.existsByFromAccountIdAndToAccountId(ctx.fromAccountId(), ctx.toAccountId())) {
            return Optional.of("Penerima baru dengan nominal Rp" + ctx.amount().toPlainString()
                    + " (pemicu mulai Rp" + min.toPlainString() + ")");
        }
        return Optional.empty();
    }

    @Override
    public void validateParams(JsonNode params) {
        Params.positiveDecimal(params, "min_amount");
    }
}
