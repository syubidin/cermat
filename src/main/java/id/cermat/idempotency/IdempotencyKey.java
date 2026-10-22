package id.cermat.idempotency;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @Column(name = "idem_key", length = 100)
    private String key;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Transaksi hasil request ini. Diisi di transaksi DB yang sama dengan pembuatan transaksinya. */
    @Column(name = "transaction_id")
    private UUID transactionId;

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, String requestHash) {
        this.key = key;
        this.requestHash = requestHash;
    }

    public String getKey() { return key; }
    public String getRequestHash() { return requestHash; }
    public UUID getTransactionId() { return transactionId; }
}
