package id.cermat.rule;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import id.cermat.transaction.LedgerTransactionRepository;

/**
 * Terlalu banyak transfer keluar dari satu rekening dalam waktu singkat.
 * Params: {"max_count": 5, "window_seconds": 60}
 *
 * Penghitungan aman dari balapan karena rekening asal sudah dikunci (SELECT ... FOR UPDATE)
 * oleh TransferService sebelum aturan dievaluasi.
 */
@Component
public class VelocityRule implements Rule {

    private final LedgerTransactionRepository transactions;

    public VelocityRule(LedgerTransactionRepository transactions) { this.transactions = transactions; }

    @Override public String code() { return "VELOCITY"; }

    @Override
    public Optional<String> evaluate(RuleContext ctx, JsonNode params) {
        int maxCount = Params.positiveInt(params, "max_count");
        int windowSeconds = Params.positiveInt(params, "window_seconds");

        long previous = transactions.countByFromAccountIdAndCreatedAtAfter(
                ctx.fromAccountId(), ctx.now().minusSeconds(windowSeconds));
        long including = previous + 1;   // + transaksi yang sedang dievaluasi

        if (including > maxCount) {
            return Optional.of(including + " transfer dalam " + windowSeconds
                    + " detik (batas " + maxCount + ")");
        }
        return Optional.empty();
    }

    @Override
    public void validateParams(JsonNode params) {
        Params.positiveInt(params, "max_count");
        Params.positiveInt(params, "window_seconds");
    }
}
