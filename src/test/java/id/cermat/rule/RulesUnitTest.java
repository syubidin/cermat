package id.cermat.rule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import id.cermat.transaction.LedgerTransactionRepository;

class RulesUnitTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final UUID FROM = UUID.randomUUID();
    static final UUID TO = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-10-22T08:00:00Z");

    private RuleContext ctx(String amount) {
        return new RuleContext(FROM, TO, new BigDecimal(amount), NOW);
    }

    private JsonNode json(String text) throws Exception { return MAPPER.readTree(text); }

    // ---------- HIGH_AMOUNT ----------
    @Test
    void highAmount_triggersOnlyAboveLimit() throws Exception {
        HighAmountRule rule = new HighAmountRule();
        JsonNode params = json("{\"max_amount\":100000000}");

        assertTrue(rule.evaluate(ctx("100000001"), params).isPresent());
        assertTrue(rule.evaluate(ctx("100000000"), params).isEmpty(), "Tepat di batas belum melebihi");
        assertTrue(rule.evaluate(ctx("5000"), params).isEmpty());
    }

    @Test
    void highAmount_reasonMentionsAmountAndLimit() throws Exception {
        String reason = new HighAmountRule().evaluate(ctx("150000000"), json("{\"max_amount\":100000000}")).orElseThrow();
        assertTrue(reason.contains("150000000") && reason.contains("100000000"), reason);
    }

    @Test
    void highAmount_rejectsInvalidParams() throws Exception {
        HighAmountRule rule = new HighAmountRule();
        assertThrows(IllegalArgumentException.class, () -> rule.validateParams(json("{}")));
        assertThrows(IllegalArgumentException.class, () -> rule.validateParams(json("{\"max_amount\":-1}")));
        assertThrows(IllegalArgumentException.class, () -> rule.validateParams(json("{\"max_amount\":\"banyak\"}")));
        assertDoesNotThrow(() -> rule.validateParams(json("{\"max_amount\":1000}")));
    }

    // ---------- NEW_PAYEE_BIG ----------
    @Test
    void newPayeeBig_triggersForUnknownPayeeAtOrAboveMinimum() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);
        when(repo.existsByFromAccountIdAndToAccountId(FROM, TO)).thenReturn(false);
        NewPayeeBigRule rule = new NewPayeeBigRule(repo);

        assertTrue(rule.evaluate(ctx("20000000"), json("{\"min_amount\":20000000}")).isPresent());
    }

    @Test
    void newPayeeBig_doesNotTriggerForKnownPayee() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);
        when(repo.existsByFromAccountIdAndToAccountId(FROM, TO)).thenReturn(true);

        assertTrue(new NewPayeeBigRule(repo).evaluate(ctx("30000000"), json("{\"min_amount\":20000000}")).isEmpty());
    }

    @Test
    void newPayeeBig_belowMinimum_doesNotEvenQueryTheDatabase() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);

        assertTrue(new NewPayeeBigRule(repo).evaluate(ctx("1000"), json("{\"min_amount\":20000000}")).isEmpty());

        verifyNoInteractions(repo);
    }

    @Test
    void newPayeeBig_rejectsInvalidParams() throws Exception {
        NewPayeeBigRule rule = new NewPayeeBigRule(mock(LedgerTransactionRepository.class));
        assertThrows(IllegalArgumentException.class, () -> rule.validateParams(json("{\"min_amount\":0}")));
        assertThrows(IllegalArgumentException.class, () -> rule.validateParams(json("[]")));
    }

    // ---------- VELOCITY ----------
    @Test
    void velocity_triggersWhenCountIncludingCurrentExceedsLimit() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);
        when(repo.countByFromAccountIdAndCreatedAtAfter(eq(FROM), any())).thenReturn(5L);   // + yang sekarang = 6

        String reason = new VelocityRule(repo)
                .evaluate(ctx("1000"), json("{\"max_count\":5,\"window_seconds\":60}")).orElseThrow();

        assertTrue(reason.contains("6 transfer"), reason);
    }

    @Test
    void velocity_doesNotTriggerExactlyAtLimit() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);
        when(repo.countByFromAccountIdAndCreatedAtAfter(eq(FROM), any())).thenReturn(4L);   // + sekarang = 5

        assertTrue(new VelocityRule(repo)
                .evaluate(ctx("1000"), json("{\"max_count\":5,\"window_seconds\":60}")).isEmpty());
    }

    @Test
    void velocity_looksBackExactlyTheConfiguredWindow() throws Exception {
        LedgerTransactionRepository repo = mock(LedgerTransactionRepository.class);

        new VelocityRule(repo).evaluate(ctx("1000"), json("{\"max_count\":5,\"window_seconds\":60}"));

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
        verify(repo).countByFromAccountIdAndCreatedAtAfter(eq(FROM), since.capture());
        assertEquals(NOW.minusSeconds(60), since.getValue());
    }

    @Test
    void velocity_rejectsInvalidParams() throws Exception {
        VelocityRule rule = new VelocityRule(mock(LedgerTransactionRepository.class));
        assertThrows(IllegalArgumentException.class,
                () -> rule.validateParams(json("{\"max_count\":0,\"window_seconds\":60}")));
        assertThrows(IllegalArgumentException.class,
                () -> rule.validateParams(json("{\"max_count\":5.5,\"window_seconds\":60}")));
        assertThrows(IllegalArgumentException.class,
                () -> rule.validateParams(json("{\"max_count\":5}")));
        assertDoesNotThrow(() -> rule.validateParams(json("{\"max_count\":5,\"window_seconds\":60}")));
    }
}
