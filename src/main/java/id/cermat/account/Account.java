package id.cermat.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import id.cermat.common.BusinessException;
import jakarta.persistence.*;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_number", nullable = false, unique = true, length = 20)
    private String accountNumber;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "IDR";

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status = AccountStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountType type = AccountType.CUSTOMER;

    /** Optimistic locking: Hibernate menaikkan angka ini tiap update dan menolak update basi. */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Account() {}

    public Account(String accountNumber, UUID ownerId) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
    }

    /** Satu-satunya cara mengurangi saldo. Aturan "tidak boleh minus" dijaga di domain. */
    public void debit(BigDecimal amount) {
        if (type == AccountType.CUSTOMER && balance.compareTo(amount) < 0) {
            throw new BusinessException("Saldo tidak cukup");
        }
        balance = balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public UUID getId() { return id; }
    public String getAccountNumber() { return accountNumber; }
    public UUID getOwnerId() { return ownerId; }
    public String getCurrency() { return currency; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public AccountType getType() { return type; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
