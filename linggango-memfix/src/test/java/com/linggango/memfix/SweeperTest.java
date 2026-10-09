package com.linggango.memfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ArrayListMultimap;

/**
 * Exercises the sweep rules directly. The predicate stands in for the server
 * liveness check so no Minecraft bootstrap is required; everything else
 * (multimap handling, nested containers, eviction) is the production path.
 */
class SweeperTest {

    /**
     * A UUID seen online and now absent is a real logout and should be dropped.
     * A UUID that has never been online must survive: the entry may be saved
     * state for a player who has not joined yet, and dropping it loses data.
     */
    @Test
    void uuidMapDropsOfflinePlayers() {
        Set<UUID> seen = new HashSet<>();
        Set<UUID> online = new HashSet<>();
        UUID loggedOut = UUID.randomUUID();
        UUID neverJoined = UUID.randomUUID();
        seen.add(loggedOut);

        assertTrue(Sweeper.isOfflinePlayer(loggedOut, seen, online), "logged-out player is stale");
        assertFalse(Sweeper.isOfflinePlayer(neverJoined, seen, online), "never-joined player is not stale");

        Map<Object, Object> map = new HashMap<>();
        map.put(loggedOut, "dropped");
        map.put(neverJoined, "kept");
        Predicate<Object> stale = k -> Sweeper.isOfflinePlayer((UUID) k, seen, online);
        int removed = Sweeper.purgeMap(map, stale);

        assertEquals(1, removed, "uuid map removed only the logged-out entry");
        assertTrue(map.containsKey(neverJoined), "uuid map kept the never-joined entry");

        Set<UUID> onlineNow = new HashSet<>();
        onlineNow.add(loggedOut);
        assertFalse(Sweeper.isOfflinePlayer(loggedOut, seen, onlineNow), "a rejoined player is no longer stale");
    }

    /** Named separately so the intent of the never-joined rule is explicit. */
    @Test
    void neverJoinedPlayerIsKept() {
        UUID id = UUID.randomUUID();
        assertFalse(Sweeper.isOfflinePlayer(id, new HashSet<>(), new HashSet<>()),
                "unknown uuid is not stale before any login");
    }

    @Test
    void liveUuidIsKept() {
        UUID online = UUID.randomUUID();
        Map<Object, Object> map = new HashMap<>();
        map.put(online, 1L);
        assertEquals(0, Sweeper.purgeMap(map, k -> false), "live uuid survives a sweep");
        assertEquals(1, map.size());
    }

    /** A player-keyed set nested as a value should vanish when all ids are stale. */
    @Test
    void nestedUuidMapIsDroppedWithItsParent() {
        Map<Object, Object> outer = new HashMap<>();
        Map<Object, Object> nested = new HashMap<>();
        nested.put(UUID.randomUUID(), "a");
        nested.put(UUID.randomUUID(), "b");
        outer.put("owner", nested);

        int removed = Sweeper.purgeMap(outer, k -> true);

        assertEquals(1, removed, "nested all-stale map was removed");
        assertTrue(outer.isEmpty(), "outer map is now empty");
    }

    /** NaturesAura keeps its player state in a Guava ListMultimap, not a Map. */
    @Test
    void guavaMultimapIsPurged() {
        ArrayListMultimap<UUID, String> mm = ArrayListMultimap.create();
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        mm.put(online, "kept");
        mm.put(offline, "dropped-1");
        mm.put(offline, "dropped-2");

        GuavaAccess.MultimapLike like = GuavaAccess.multimapOf(mm);
        assertNotNull(like, "multimap adapter recognises a Guava multimap");

        int removed = Sweeper.purgeMultimap(like, k -> !k.equals(online));

        assertEquals(1, removed, "multimap removed the offline key once");
        assertTrue(mm.containsKey(online), "multimap kept the online key");
        assertFalse(mm.containsKey(offline), "multimap dropped every value of the offline key");
        assertEquals(1, mm.size(), "multimap size is now 1");
    }

    /** Item-stack maps are caches; growth is bounded by evicting the oldest. */
    @Test
    void itemStackMapIsBounded() {
        Map<Object, Object> map = new HashMap<>();
        for (int i = 0; i < Sweeper.ITEM_STACK_MAP_CAP + 500; i++) {
            map.put("stack-" + i, i);
        }
        int before = map.size();
        int removed = Sweeper.evictOldest(map, Sweeper.ITEM_STACK_MAP_CAP);

        assertEquals(before - Sweeper.ITEM_STACK_MAP_CAP, removed, "eviction removed the overflow");
        assertEquals(Sweeper.ITEM_STACK_MAP_CAP, map.size(), "map is back at the cap");
    }

    /** Unknown key types must never be evicted, or mods would lose live data. */
    @Test
    void unrecognisedKeyIsKept() {
        Map<Object, Object> map = new HashMap<>();
        map.put("a-plain-string-key", "value");
        map.put(42, "value");
        assertEquals(0, Sweeper.purgeMap(map, k -> false), "unrecognised keys are untouched");
        assertEquals(2, map.size());
    }

    @Test
    void nullKeyIsDropped() {
        Map<Object, Object> map = new HashMap<>();
        map.put(null, "orphan");
        int removed = Sweeper.purgeMap(map, k -> k == null);
        assertEquals(1, removed, "null key is dropped");
        assertTrue(map.isEmpty());
    }
}
