package com.linggango.memfix;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Purges stale entries from other mods' static collections.
 *
 * <p>Every target is a class name plus a field name. The container is reached
 * reflectively so this mod carries no compile-time dependency on the mods it
 * repairs, and an absent mod simply means its targets are skipped.
 *
 * <p>A key is stale when the server can no longer reach it: a removed entity, a
 * player who has left, an unloaded level, an empty item stack. Key types are
 * recognised at runtime, so an unrecognised key is never treated as stale and
 * an unparseable target degrades to a skip rather than a crash.
 *
 * <p>Item-stack keyed maps need a second rule. An ItemStack carries its own
 * copy of its contents, so a mod that uses one as a key creates an entry no
 * later lookup can match; such maps grow forever even though no key is ever
 * "removed". They are bounded instead, dropping the earliest insertions.
 */
public final class Sweeper {

    /**
     * Maps keyed by item stack are capped here. They are caches, so eviction is
     * safe, but the cap is deliberately generous so a normal working set is
     * never touched.
     */
    static final int ITEM_STACK_MAP_CAP = 8192;

    private Sweeper() {
    }

    public static final class Target {
        final String className;
        final String fieldName;
        volatile Field field;
        volatile boolean broken;

        public Target(String className, String fieldName) {
            this.className = className;
            this.fieldName = fieldName;
        }

        boolean ensure() {
            if (field != null) {
                return true;
            }
            if (broken) {
                return false;
            }
            try {
                Field f = Class.forName(className).getDeclaredField(fieldName);
                if (!Modifier.isStatic(f.getModifiers())) {
                    MemFix.LOGGER.warn("[memfix] {}#{} is not static; skipping", className, fieldName);
                    broken = true;
                    return false;
                }
                f.setAccessible(true);
                field = f;
                return true;
            } catch (Throwable t) {
                // Absent mod, renamed field, or a class that will not link:
                // none of these should take the server down.
                broken = true;
                return false;
            }
        }
    }

    private static volatile List<Target> targets = List.of();
    private static long lastSweepMillis;
    private static long totalPurged;

    /** Player ids seen online this run, so a logout is distinguishable from a never-join. */
    private static final Set<UUID> seenOnline = new HashSet<>();

    public static synchronized void setTargets(List<Target> t) {
        targets = List.copyOf(t);
    }

    public static long getTotalPurged() {
        return totalPurged;
    }

    public static boolean dueForSweep(long intervalMillis) {
        return System.currentTimeMillis() - lastSweepMillis >= intervalMillis;
    }

    /** Returns the number of entries dropped across all registered targets. */
    public static synchronized int sweep(MinecraftServer server) {
        List<Target> snapshot = targets;
        if (snapshot.isEmpty()) {
            return 0;
        }
        Set<UUID> online = onlinePlayerIds(server);
        seenOnline.addAll(online);
        Predicate<Object> stale = key -> isStale(key, seenOnline, online, server);

        int purged = 0;
        for (Target t : snapshot) {
            if (!t.ensure()) {
                continue;
            }
            try {
                purged += purge(t.field.get(null), stale);
            } catch (Throwable ignored) {
                // A target that throws is retried on the next sweep.
            }
        }
        totalPurged += purged;
        lastSweepMillis = System.currentTimeMillis();
        return purged;
    }

    private static int purge(Object container, Predicate<Object> stale) {
        if (container instanceof GuavaAccess.MultimapLike m) {
            return purgeMultimap(m, stale);
        }
        int removed = 0;
        if (container instanceof Map<?, ?> map) {
            removed = purgeMap(Cast.map(map), stale);
        }
        if (container instanceof Collection<?> col) {
            removed += purgeCollection(Cast.collection(col));
        }
        return removed;
    }

    static int purgeMap(Map<Object, Object> map, Predicate<Object> stale) {
        int removed = 0;
        try {
            Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
            boolean itemKeyed = false;
            while (it.hasNext()) {
                Map.Entry<Object, Object> e = it.next();
                if (e.getKey() instanceof ItemStack) {
                    itemKeyed = true;
                }
                if (stale.test(e.getKey()) || isStaleValue(e.getValue(), stale)) {
                    it.remove();
                    removed++;
                }
            }
            if (itemKeyed) {
                removed += evictOldest(map, ITEM_STACK_MAP_CAP);
            }
        } catch (Throwable ignored) {
            // Owner mutated the map from its own thread; retry next sweep.
        }
        return removed;
    }

    /**
     * Drops insertions from the front of the iteration order until the map is
     * back under {@code cap}. HashMap order is not true insertion order, so this
     * is a heuristic: it bounds growth without needing per-entry timestamps from
     * a mod we cannot change.
     */
    static int evictOldest(Map<Object, Object> map, int cap) {
        if (map.size() <= cap) {
            return 0;
        }
        int excess = map.size() - cap;
        int removed = 0;
        try {
            Iterator<Object> it = map.keySet().iterator();
            while (it.hasNext() && removed < excess) {
                it.next();
                it.remove();
                removed++;
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    private static int purgeCollection(Collection<Object> col) {
        int removed = 0;
        try {
            Iterator<Object> it = col.iterator();
            while (it.hasNext()) {
                if (isStale(it.next(), Set.of(), Set.of(), null)) {
                    it.remove();
                    removed++;
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    /**
     * Guava multimaps are not {@code java.util.Map}, so the map path would skip
     * them. An entry is dropped only when its key is stale, and any failure to
     * rewrite the key view is ignored.
     */
    static int purgeMultimap(GuavaAccess.MultimapLike m, Predicate<Object> stale) {
        int removed = 0;
        try {
            for (Object key : new ArrayList<>(m.keySetView())) {
                if (stale.test(key)) {
                    m.removeAll(key);
                    removed++;
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    /**
     * Drops a value that is itself a container whose every element is stale, so
     * nested player-keyed caches are cleared too.
     */
    private static boolean isStaleValue(Object value, Predicate<Object> stale) {
        if (value instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return false;
            }
            for (Object o : c) {
                if (!isStale(o, Set.of(), Set.of(), null)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return false;
            }
            Map<Object, Object> inner = Cast.map(m);
            return purgeMap(inner, stale) == m.size();
        }
        return false;
    }

    static boolean isStale(Object key, Set<UUID> seenOnline, Set<UUID> online, MinecraftServer server) {
        if (key == null) {
            return true;
        }
        if (key instanceof Entity e) {
            return e.isRemoved();
        }
        if (key instanceof ItemStack stack) {
            return stack.isEmpty();
        }
        if (key instanceof UUID id) {
            return isOfflinePlayer(id, seenOnline, online);
        }
        if (key instanceof ServerLevel sl) {
            return !liveLevels(server).contains(sl);
        }
        return false;
    }

    /**
     * Only a player we watched log in and then leave is treated as stale.
     * Without that history a UUID could belong to a player who has simply not
     * joined yet, and dropping their saved data would be a silent data loss.
     */
    static boolean isOfflinePlayer(UUID id, Set<UUID> seenOnline, Set<UUID> online) {
        return seenOnline.contains(id) && !online.contains(id);
    }

    private static Set<ServerLevel> liveLevels(MinecraftServer server) {
        Set<ServerLevel> live = new HashSet<>();
        if (server == null) {
            return live;
        }
        try {
            for (ServerLevel l : server.getAllLevels()) {
                live.add(l);
            }
        } catch (Throwable ignored) {
        }
        return live;
    }

    private static Set<UUID> onlinePlayerIds(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>();
        if (server == null) {
            return ids;
        }
        try {
            for (var p : server.getPlayerList().getPlayers()) {
                ids.add(p.getUUID());
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    static List<Target> parse(List<String> entries) {
        List<Target> out = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null) {
                continue;
            }
            String s = raw.trim();
            int hash = s.indexOf('#');
            if (hash <= 0 || hash == s.length() - 1) {
                MemFix.LOGGER.warn("[memfix] ignoring malformed target '{}' (want 'package.Class#field')", s);
                continue;
            }
            out.add(new Target(s.substring(0, hash), s.substring(hash + 1)));
        }
        return out;
    }

    private static final class Cast {
        @SuppressWarnings("unchecked")
        static Map<Object, Object> map(Map<?, ?> m) {
            return (Map<Object, Object>) m;
        }

        @SuppressWarnings("unchecked")
        static Collection<Object> collection(Collection<?> c) {
            return (Collection<Object>) c;
        }
    }
}
