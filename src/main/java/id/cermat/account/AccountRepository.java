package id.cermat.account;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    boolean existsByAccountNumber(String accountNumber);
    List<Account> findByOwnerId(UUID ownerId);

    /**
     * SELECT ... FOR UPDATE dengan urutan tetap (ORDER BY id).
     * Urutan yang konsisten mencegah deadlock saat A->B dan B->A terjadi bersamaan.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> lockAllByIdInOrderById(@Param("ids") Collection<UUID> ids);
}
