package id.cermat.account;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import id.cermat.common.BusinessException;

class AccountTest {

    private Account customer() { return new Account("0000000099", UUID.randomUUID()); }

    private void assertBalance(Account a, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(a.getBalance()), "Saldo: " + a.getBalance());
    }

    @Test
    void creditThenDebit_updatesBalance() {
        Account a = customer();
        a.credit(new BigDecimal("100"));
        a.debit(new BigDecimal("30"));
        assertBalance(a, "70");
    }

    @Test
    void debitMoreThanBalance_isRejected_andBalanceUnchanged() {
        Account a = customer();
        a.credit(new BigDecimal("100"));

        BusinessException e = assertThrows(BusinessException.class, () -> a.debit(new BigDecimal("100.01")));

        assertEquals("Saldo tidak cukup", e.getMessage());
        assertBalance(a, "100");
    }

    @Test
    void debitExactBalance_isAllowed() {
        Account a = customer();
        a.credit(new BigDecimal("100"));
        a.debit(new BigDecimal("100"));
        assertBalance(a, "0");
    }

    @Test
    void systemAccount_mayGoNegative() {
        Account cash = customer();
        ReflectionTestUtils.setField(cash, "type", AccountType.SYSTEM);

        cash.debit(new BigDecimal("500"));

        assertBalance(cash, "-500");
    }

    @Test
    void freezeAndUnfreeze_changeStatus() {
        Account a = customer();
        assertEquals(AccountStatus.ACTIVE, a.getStatus());

        a.freeze();
        assertEquals(AccountStatus.FROZEN, a.getStatus());

        a.unfreeze();
        assertEquals(AccountStatus.ACTIVE, a.getStatus());
    }
}
