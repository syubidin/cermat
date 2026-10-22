package id.cermat.idempotency;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /**
     * Klaim key secara atomik di level database.
     * Hasil 1 = key baru, request ini yang berhak memproses.
     * Hasil 0 = key sudah ada (request ulang / duplikat).
     *
     * Jika ada transaksi lain yang sedang meng-insert key yang sama, PostgreSQL membuat
     * statement ini MENUNGGU sampai transaksi itu commit/rollback.
     */
    @Modifying
    @Query(value = """
            insert into idempotency_keys (idem_key, request_hash)
            values (:key, :hash)
            on conflict (idem_key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("key") String key, @Param("hash") String hash);

    /** Menautkan key ke transaksi yang dihasilkannya (dalam transaksi DB yang sama). */
    @Modifying
    @Query(value = "update idempotency_keys set transaction_id = :txId where idem_key = :key",
            nativeQuery = true)
    int attachTransaction(@Param("key") String key, @Param("txId") UUID txId);
}
