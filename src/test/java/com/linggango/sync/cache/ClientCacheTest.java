package com.linggango.sync.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers cache hit/miss, hashing, eviction bounds, expiration and on-disk round trips. */
class ClientCacheTest {

    private static byte[] bytes(int length, int fill) {
        byte[] data = new byte[length];
        java.util.Arrays.fill(data, (byte) fill);
        return data;
    }

    @Test
    void storesAndRetrievesByVersion() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        cache.put("ae2:1234", 42, Hashes.of("payload"), "payload".getBytes());

        assertTrue(cache.hasVersion("ae2:1234", 42, null));
        assertFalse(cache.hasVersion("ae2:1234", 43, null));
        assertEquals(1, cache.size());
    }

    @Test
    void hashWinsOverStaleVersionCounter() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        String payload = "same-data";
        cache.put("k", 7, Hashes.of(payload), payload.getBytes());

        // A client whose counter drifted but whose data is identical must still hit, which is
        // what prevents an unnecessary resend after a client restart.
        assertTrue(cache.hasVersion("k", 999, Hashes.of(payload)));
        assertFalse(cache.hasVersion("k", 7, Hashes.of("different")));
    }

    @Test
    void returnsDefensiveCopies() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        byte[] original = bytes(8, 1);
        cache.put("k", 1, "h", original);

        original[0] = 99;
        byte[] first = cache.get("k").orElseThrow().data();
        first[1] = 99;

        byte[] second = cache.get("k").orElseThrow().data();
        assertEquals(1, second[0], "cache must not alias the caller's array");
        assertEquals(1, second[1], "cache must not hand out its internal array");
    }

    @Test
    void invalidateRemovesEntry() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        cache.put("k", 1, "h", bytes(4, 0));
        cache.invalidate("k");
        assertTrue(cache.get("k").isEmpty());
        assertEquals(0, cache.bytes());
    }

    @Test
    void invalidateNamespaceOnlyTouchesThatNamespace() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        cache.put("ae2:a", 1, "h", bytes(4, 0));
        cache.put("ae2:b", 1, "h", bytes(4, 0));
        cache.put("create:a", 1, "h", bytes(4, 0));

        assertEquals(2, cache.invalidateNamespace("ae2"));
        assertTrue(cache.get("create:a").isPresent());
    }

    @Test
    void evictsLeastRecentlyUsedWhenOverEntryLimit() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 3, 1 << 20, 0);
        cache.put("a", 1, "h", bytes(4, 0));
        cache.put("b", 1, "h", bytes(4, 0));
        cache.put("c", 1, "h", bytes(4, 0));

        cache.get("a"); // 'a' becomes most recent, 'b' is now the coldest
        cache.put("d", 1, "h", bytes(4, 0));

        assertEquals(3, cache.size());
        assertTrue(cache.get("b").isEmpty(), "coldest entry should be evicted");
        assertTrue(cache.get("a").isPresent(), "recently used entry should survive");
        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void evictsWhenOverByteLimit() {
        // The cache enforces a 1 KB floor so a misconfigured byte budget cannot make it
        // useless; this test therefore works above that floor.
        ClientCache cache = new ClientCache(CacheStore.NOOP, 1000, 1024, 0);
        cache.put("a", 1, "h", bytes(700, 0));
        cache.put("b", 1, "h", bytes(700, 0));

        assertTrue(cache.bytes() <= 1024, "byte budget must be respected, was " + cache.bytes());
        assertEquals(1, cache.size());
    }

    @Test
    void expiredEntriesAreNotServed() {
        byte[] payload = bytes(4, 0);
        CacheEntry stale = new CacheEntry("k", 1, "h", payload, System.currentTimeMillis() - 60_000L);
        CacheStore preloaded = new CacheStore() {
            @Override
            public Map<String, CacheEntry> load() {
                return Map.of("k", stale);
            }

            @Override
            public void save(Map<String, CacheEntry> entries) {
                // not needed for this test
            }
        };

        ClientCache cache = new ClientCache(preloaded, 16, 1 << 20, 1);
        assertTrue(cache.get("k").isEmpty(), "an entry older than the TTL must not be served");
        assertEquals(0, cache.size(), "a served-miss on an expired entry should drop it");
    }

    @Test
    void clearExpiredDropsOnlyStaleEntries() {
        byte[] payload = bytes(4, 0);
        long now = System.currentTimeMillis();
        CacheEntry fresh = new CacheEntry("fresh", 1, "h", payload, now);
        CacheEntry stale = new CacheEntry("stale", 1, "h", payload, now - 60_000L);
        CacheStore preloaded = new CacheStore() {
            @Override
            public Map<String, CacheEntry> load() {
                return Map.of("fresh", fresh, "stale", stale);
            }

            @Override
            public void save(Map<String, CacheEntry> entries) {
                // not needed for this test
            }
        };

        ClientCache cache = new ClientCache(preloaded, 16, 1 << 20, 10);
        assertEquals(1, cache.clearExpired());
        assertTrue(cache.get("fresh").isPresent());
        assertTrue(cache.get("stale").isEmpty());
    }

    @Test
    void corruptPersistedDataIsDiscardedRatherThanThrown(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.bin");
        Files.write(file, "this is not a cache file, it is garbage".getBytes());

        DiskCacheStore store = new DiskCacheStore(file, false);
        ClientCache cache = new ClientCache(store, 16, 1 << 20, 0);

        assertEquals(0, cache.size(), "a corrupt cache must degrade to empty, not fail");
    }

    @Test
    void roundTripsThroughDisk(@TempDir Path dir) {
        Path file = dir.resolve("nested").resolve("cache.bin");
        ClientCache first = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        first.put("ae2:1", 42, Hashes.of("abc"), "abc".getBytes());
        first.put("create:2", 7, Hashes.of("xyz"), bytes(1024, 5));
        first.flush();

        assertTrue(Files.exists(file), "flush should create the file, creating parent dirs as needed");

        ClientCache second = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        assertEquals(2, second.size());
        assertTrue(second.hasVersion("ae2:1", 42, null));
        assertArrayEquals(bytes(1024, 5), second.get("create:2").orElseThrow().data());
    }

    @Test
    void roundTripsThroughCompressedDisk(@TempDir Path dir) {
        Path file = dir.resolve("cache.bin");
        ClientCache first = new ClientCache(new DiskCacheStore(file, true), 16, 1 << 20, 0);
        first.put("k", 3, "h", bytes(4096, 7));
        first.flush();

        ClientCache second = new ClientCache(new DiskCacheStore(file, true), 16, 1 << 20, 0);
        assertArrayEquals(bytes(4096, 7), second.get("k").orElseThrow().data());
    }

    @Test
    void truncatedFileIsDiscarded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cache.bin");
        ClientCache first = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        first.put("k", 1, "h", bytes(512, 3));
        first.flush();

        byte[] full = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(full, full.length / 2));

        ClientCache second = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        assertEquals(0, second.size(), "a truncated cache must be discarded");
    }

    @Test
    void clearEmptiesMemoryAndDisk(@TempDir Path dir) {
        Path file = dir.resolve("cache.bin");
        ClientCache cache = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        cache.put("k", 1, "h", bytes(8, 1));
        cache.flush();
        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0, cache.bytes());
        ClientCache reopened = new ClientCache(new DiskCacheStore(file, false), 16, 1 << 20, 0);
        assertEquals(0, reopened.size());
    }

    @Test
    void statsTrackHitsAndMisses() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        cache.put("k", 1, "h", bytes(4, 0));
        cache.get("k");
        cache.get("missing");

        ClientCache.Stats stats = cache.stats();
        assertEquals(1, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(0.5, stats.hitRate(), 1e-9);
    }

    @Test
    void hashesDifferForDifferentContent() {
        assertNotEquals(Hashes.of("a"), Hashes.of("b"));
        assertEquals(Hashes.of("a"), Hashes.of("a"));
        assertEquals(64, Hashes.sha256("a".getBytes()).length());
    }

    @Test
    void noopStorePersistsNothing() {
        ClientCache cache = new ClientCache(CacheStore.NOOP, 16, 1 << 20, 0);
        cache.put("k", 1, "h", bytes(4, 0));
        cache.flush();
        Map<String, CacheEntry> loaded = CacheStore.NOOP.load();
        assertTrue(loaded.isEmpty());
    }
}
