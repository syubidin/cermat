package id.cermat.transaction;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Kumpulan rekening kas sistem (shard). Setiap setoran mengunci SATU baris kas; dengan satu baris saja,
 * semua setoran antre. Dengan N shard, setoran tersebar dan bisa berjalan paralel.
 *
 * Shard dipilih dari hash idempotency key: key yang sama selalu ke shard yang sama (deterministik),
 * sedangkan key berbeda tersebar merata. Total kas = jumlah saldo seluruh shard.
 */
@Component
public class CashPool {

    public static final int MAX_SHARDS = 16;   // jumlah shard yang di-seed oleh migrasi V2 + V5

    private final List<UUID> activeShards;
    private final Set<UUID> allCashAccounts;

    /** cermat.cash.shards = jumlah shard aktif (default 16). Nilai 1 meniru perilaku lama (untuk benchmark). */
    public CashPool(@Value("${cermat.cash.shards:16}") int activeCount) {
        if (activeCount < 1 || activeCount > MAX_SHARDS) {
            throw new IllegalStateException("cermat.cash.shards harus antara 1 dan " + MAX_SHARDS);
        }
        List<UUID> all = IntStream.rangeClosed(1, MAX_SHARDS)
                .mapToObj(n -> UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n)))
                .toList();
        this.activeShards = all.subList(0, activeCount);
        this.allCashAccounts = Set.copyOf(all);
    }

    public UUID shardFor(String idemKey) {
        return activeShards.get(Math.floorMod(idemKey.hashCode(), activeShards.size()));
    }

    /** Berlaku untuk semua rekening kas, termasuk shard yang sedang tidak aktif. */
    public boolean isCash(UUID accountId) {
        return allCashAccounts.contains(accountId);
    }
}
