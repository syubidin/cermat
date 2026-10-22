package id.cermat.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import id.cermat.account.Account;
import id.cermat.account.AccountRepository;
import id.cermat.account.AccountType;
import id.cermat.audit.AuditService;
import id.cermat.common.BusinessException;
import id.cermat.idempotency.Hashing;
import id.cermat.idempotency.IdempotencyKey;
import id.cermat.idempotency.IdempotencyKeyRepository;
import id.cermat.ledger.Direction;
import id.cermat.ledger.LedgerEntry;
import id.cermat.ledger.LedgerEntryRepository;
import id.cermat.rule.FraudFlagRepository;
import id.cermat.rule.RuleEngine;
import id.cermat.rule.RuleHit;

/** Menguji ALUR keputusan TransferService tanpa database: semua dependensi ditiru dengan Mockito. */
class TransferServiceTest {

    AccountRepository accounts = mock(AccountRepository.class);
    LedgerTransactionRepository transactions = mock(LedgerTransactionRepository.class);
    LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    IdempotencyKeyRepository keys = mock(IdempotencyKeyRepository.class);
    AuditService audit = mock(AuditService.class);
    RuleEngine rules = mock(RuleEngine.class);
    FraudFlagRepository flags = mock(FraudFlagRepository.class);
    CashPool cashPool = new CashPool(16);

    TransferService service;
    UUID fromId = UUID.randomUUID();
    UUID toId = UUID.randomUUID();
    AtomicReference<LedgerTransaction> savedTx = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new TransferService(accounts, transactions, entries, keys, audit, rules, flags, cashPool);
        when(keys.insertIfAbsent(anyString(), anyString())).thenReturn(1);   // key baru
        when(rules.evaluate(any())).thenReturn(List.of());                   // tidak ada aturan terpicu
        when(transactions.saveAndFlush(any())).thenAnswer(inv -> {
            LedgerTransaction tx = inv.getArgument(0);
            ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());       // id biasanya dibuat JPA
            savedTx.set(tx);
            return tx;
        });
    }

    private Account account(UUID id, String balance) {
        Account a = new Account("00000000" + id.toString().substring(0, 2), UUID.randomUUID());
        ReflectionTestUtils.setField(a, "id", id);
        a.credit(new BigDecimal(balance));
        return a;
    }

    private void givenAccounts(Account from, Account to) {
        when(accounts.lockAllByIdInOrderById(any())).thenReturn(List.of(from, to));
    }

    @Test
    void sameAccount_isRejected_beforeTouchingAnything() {
        assertThrows(BusinessException.class,
                () -> service.transfer("k1", fromId, fromId, new BigDecimal("1000"), null));
        verifyNoInteractions(keys, accounts);
    }

    @Test
    void blankIdempotencyKey_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer("  ", fromId, toId, new BigDecimal("1000"), null));
        assertThrows(IllegalArgumentException.class,
                () -> service.deposit("", toId, new BigDecimal("1000"), null));
    }

    @Test
    void insufficientFunds_throws_andWritesNothing() {
        givenAccounts(account(fromId, "500"), account(toId, "0"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.transfer("k1", fromId, toId, new BigDecimal("1000"), null));

        assertEquals("Saldo tidak cukup", e.getMessage());
        verify(transactions, never()).saveAndFlush(any());
        verifyNoInteractions(entries, audit, flags);
    }

    @Test
    void successfulTransfer_writesBalancedLedgerEntriesAndMovesMoney() {
        Account from = account(fromId, "5000");
        Account to = account(toId, "0");
        givenAccounts(from, to);

        TransferResult result = service.transfer("k1", fromId, toId, new BigDecimal("1000"), "bayar");

        assertFalse(result.replayed());
        assertEquals(TxStatus.SUCCESS, result.transaction().getStatus());
        assertEquals(0, new BigDecimal("4000").compareTo(from.getBalance()));
        assertEquals(0, new BigDecimal("1000").compareTo(to.getBalance()));

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entries, times(2)).save(captor.capture());
        LedgerEntry debit = captor.getAllValues().stream().filter(e -> e.getDirection() == Direction.DEBIT).findFirst().orElseThrow();
        LedgerEntry credit = captor.getAllValues().stream().filter(e -> e.getDirection() == Direction.CREDIT).findFirst().orElseThrow();
        assertEquals(fromId, debit.getAccountId());
        assertEquals(toId, credit.getAccountId());
        assertEquals(0, debit.getAmount().compareTo(credit.getAmount()), "Debit dan kredit harus seimbang");
        assertEquals(0, new BigDecimal("4000").compareTo(debit.getBalanceAfter()));
        assertEquals(0, new BigDecimal("1000").compareTo(credit.getBalanceAfter()));

        verify(keys).attachTransaction(eq("k1"), any());
        verifyNoInteractions(flags);
    }

    @Test
    void ruleHit_flagsTransaction_butMoneyStillMoves() {
        Account from = account(fromId, "5000");
        Account to = account(toId, "0");
        givenAccounts(from, to);
        when(rules.evaluate(any())).thenReturn(List.of(new RuleHit("HIGH_AMOUNT", "terlalu besar")));

        TransferResult result = service.transfer("k1", fromId, toId, new BigDecimal("1000"), null);

        assertEquals(TxStatus.FLAGGED, result.transaction().getStatus());
        assertEquals(0, new BigDecimal("1000").compareTo(to.getBalance()), "Opsi A: uang tetap berpindah");
        verify(flags).insert(eq(result.transaction().getId()), anyList());
    }

    @Test
    void replay_returnsExistingTransaction_withoutTouchingAccounts() {
        when(keys.insertIfAbsent(anyString(), anyString())).thenReturn(0);   // key sudah ada
        UUID txId = UUID.randomUUID();
        String hash = Hashing.sha256(fromId + "|" + toId + "|1000.00|");     // rumus hash yang sama dengan service
        IdempotencyKey existing = new IdempotencyKey("k1", hash);
        ReflectionTestUtils.setField(existing, "transactionId", txId);
        LedgerTransaction original = new LedgerTransaction("TRX-1", "k1", fromId, toId,
                new BigDecimal("1000.00"), TxStatus.SUCCESS, null);
        when(keys.findById("k1")).thenReturn(Optional.of(existing));
        when(transactions.findById(txId)).thenReturn(Optional.of(original));

        TransferResult result = service.transfer("k1", fromId, toId, new BigDecimal("1000"), null);

        assertTrue(result.replayed());
        assertSame(original, result.transaction());
        verifyNoInteractions(accounts, entries);
        verify(transactions, never()).saveAndFlush(any());
    }

    @Test
    void replay_withDifferentPayload_isRejected() {
        when(keys.insertIfAbsent(anyString(), anyString())).thenReturn(0);
        when(keys.findById("k1")).thenReturn(Optional.of(new IdempotencyKey("k1", "hash-yang-berbeda")));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.transfer("k1", fromId, toId, new BigDecimal("1000"), null));

        assertTrue(e.getMessage().contains("request yang berbeda"));
        verifyNoInteractions(accounts, entries);
    }

    @Test
    void deposit_usesCashShard_andSkipsFraudRules() {
        String key = "dep-xyz";
        UUID cashId = cashPool.shardFor(key);
        Account cash = account(cashId, "0");
        ReflectionTestUtils.setField(cash, "type", AccountType.SYSTEM);
        Account customer = account(toId, "0");
        givenAccounts(cash, customer);

        TransferResult result = service.deposit(key, toId, new BigDecimal("1000"), null);

        assertEquals(cashId, result.transaction().getFromAccountId());
        assertEquals(0, new BigDecimal("1000").compareTo(customer.getBalance()));
        assertEquals(0, new BigDecimal("-1000").compareTo(cash.getBalance()));
        verifyNoInteractions(rules);   // setoran dari kas tidak dipantau aturan fraud
    }
}
