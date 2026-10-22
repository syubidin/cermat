package id.cermat.transaction;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CashPoolTest {

    private static UUID shard(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    @Test
    void sameKey_alwaysGoesToSameShard() {
        CashPool pool = new CashPool(16);
        UUID first = pool.shardFor("dep-abc-123");
        for (int i = 0; i < 100; i++) {
            assertEquals(first, pool.shardFor("dep-abc-123"));
        }
    }

    @Test
    void shardsStayWithinActiveRange() {
        CashPool pool = new CashPool(4);
        Set<UUID> allowed = Set.of(shard(1), shard(2), shard(3), shard(4));
        for (int i = 0; i < 1000; i++) {
            assertTrue(allowed.contains(pool.shardFor("key-" + i)));
        }
    }

    @Test
    void keysSpreadAcrossAllSixteenShards() {
        CashPool pool = new CashPool(16);
        Set<UUID> used = new HashSet<>();
        for (int i = 0; i < 2000; i++) used.add(pool.shardFor("key-" + i));
        assertEquals(16, used.size());
    }

    @Test
    void isCash_coversAllSixteenAccounts_evenWhenOnlyOneIsActive_butNotCustomers() {
        CashPool pool = new CashPool(1);
        for (int n = 1; n <= 16; n++) assertTrue(pool.isCash(shard(n)), "shard " + n);
        assertFalse(pool.isCash(UUID.randomUUID()));
    }

    @Test
    void invalidShardCount_failsFast() {
        assertThrows(IllegalStateException.class, () -> new CashPool(0));
        assertThrows(IllegalStateException.class, () -> new CashPool(17));
    }
}
