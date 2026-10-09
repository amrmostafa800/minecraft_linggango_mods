package com.linggango.opt.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests: no game launch, no Minecraft classes. */
class DatapackCacheTest {

    @Test
    void roundTripSurvivesRestart(@TempDir Path dir) {
        Path file = dir.resolve("datapacks.bin");
        DatapackCache cache = new DatapackCache();
        byte[] payload = {1, 2, 3, 4, 5};
        cache.put(DatapackCache.Channel.RECIPES, new DatapackCache.Entry("abc", payload));
        cache.store(file);

        DatapackCache reloaded = DatapackCache.load(file);
        assertTrue(reloaded.matches(DatapackCache.Channel.RECIPES, "abc"));
        assertArrayEquals(payload, reloaded.get(DatapackCache.Channel.RECIPES).data());
    }

    @Test
    void corruptCacheFallsBackToEmpty(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("datapacks.bin");
        java.nio.file.Files.write(file, new byte[]{9, 9, 9, 9, 9, 9});
        DatapackCache cache = DatapackCache.load(file);
        assertTrue(cache.snapshot().isEmpty());
    }

    @Test
    void hashMismatchIsRejected(@TempDir Path dir) {
        DatapackCache cache = new DatapackCache();
        cache.put(DatapackCache.Channel.TAGS, new DatapackCache.Entry("aaa", new byte[]{7}));
        assertFalse(cache.matches(DatapackCache.Channel.TAGS, "bbb"));
        assertTrue(cache.matches(DatapackCache.Channel.TAGS, "aaa"));
    }

    @Test
    void serverClaimsMatchOnlyExactHash() {
        ServerClaims claims = new ServerClaims();
        UUID player = UUID.randomUUID();
        claims.claim(player, DatapackCache.Channel.RECIPES, "hash-1");
        assertTrue(claims.matches(player, DatapackCache.Channel.RECIPES, "hash-1"));
        assertFalse(claims.matches(player, DatapackCache.Channel.RECIPES, "hash-2"));
        assertFalse(claims.matches(UUID.randomUUID(), DatapackCache.Channel.RECIPES, "hash-1"));
    }

    @Test
    void serverClaimsPersistAcrossRestart(@TempDir Path dir) {
        Path file = dir.resolve("claims.bin");
        ServerClaims claims = new ServerClaims();
        UUID player = UUID.randomUUID();
        claims.claim(player, DatapackCache.Channel.TAGS, "zzz");
        claims.store(file);

        ServerClaims reloaded = ServerClaims.load(file);
        assertTrue(reloaded.matches(player, DatapackCache.Channel.TAGS, "zzz"));
    }

    @Test
    void sha256IsStableAndDistinguishing() {
        byte[] a = {1, 2, 3};
        byte[] b = {1, 2, 4};
        assertEquals(Hashes.sha256(a), Hashes.sha256(a));
        assertNotEquals(Hashes.sha256(a), Hashes.sha256(b));
    }
}
