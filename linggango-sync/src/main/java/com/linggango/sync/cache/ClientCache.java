package com.linggango.sync.cache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, expiring, optionally persistent cache of server-owned data held on the client.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Memory and disk growth are bounded by both entry count and total byte size.
 *       Eviction is LRU, so the objects a player actually opens stay cached.</li>
 *   <li>Every operation is safe to call from the netty event loop and the client thread.</li>
 *   <li>Nothing here decides policy: callers ask for a key and either get a hit or a miss.
 *       A miss is always safe, because it degrades to "ask the server".</li>
 * </ul>
 */
public final class ClientCache {

    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final Deque<String> lru = new ArrayDeque<>();
    private final AtomicLong bytes = new AtomicLong();

    private final CacheStore store;
    private final int maxEntries;
    private final long maxBytes;
    private final long ttlSeconds;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    public ClientCache(CacheStore store, int maxEntries, long maxBytes, long ttlSeconds) {
        this.store = store == null ? CacheStore.NOOP : store;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1024L, maxBytes);
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        for (Map.Entry<String, CacheEntry> entry : this.store.load().entrySet()) {
            CacheEntry value = entry.getValue();
            if (value == null || value.key() == null) {
                rejected.incrementAndGet();
                continue;
            }
            entries.put(value.key(), value);
            lru.addLast(value.key());
            bytes.addAndGet(value.rawLength());
        }
        evictIfNeeded(System.currentTimeMillis());
    }

    public Optional<CacheEntry> get(String key) {
        if (key == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (entry.expired(now, ttlSeconds)) {
            remove(key);
            expired.incrementAndGet();
            misses.incrementAndGet();
            return Optional.empty();
        }
        entry.touch(now);
        synchronized (lru) {
            lru.remove(key);
            lru.addLast(key);
        }
        hits.incrementAndGet();
        return Optional.of(entry);
    }

    /** True when the client already holds this exact version/hash. */
    public boolean hasVersion(String key, long version, String hash) {
        return get(key).map(entry -> entry.matches(version, hash)).orElse(false);
    }

    public void put(String key, long version, String hash, byte[] data) {
        if (key == null || data == null) {
            rejected.incrementAndGet();
            return;
        }
        long now = System.currentTimeMillis();
        // Copy on the way in: the caller's array may be a reused scratch buffer, and a
        // cached entry that silently changes underneath us would defeat the version check.
        CacheEntry entry = new CacheEntry(key, version, hash, java.util.Arrays.copyOf(data, data.length), now);
        CacheEntry previous = entries.put(key, entry);
        if (previous != null) {
            bytes.addAndGet(-previous.rawLength());
            synchronized (lru) {
                lru.remove(key);
            }
        }
        bytes.addAndGet(entry.rawLength());
        synchronized (lru) {
            lru.addLast(key);
        }
        evictIfNeeded(now);
    }

    public void invalidate(String key) {
        if (key != null) {
            remove(key);
        }
    }

    /** Drops every entry whose namespace prefix matches, e.g. {@code invalidateNamespace("ae2")}. */
    public int invalidateNamespace(String namespace) {
        if (namespace == null) {
            return 0;
        }
        String prefix = namespace + ':';
        int removed = 0;
        for (String key : new ArrayList<>(entries.keySet())) {
            if (key.startsWith(prefix)) {
                remove(key);
                removed++;
            }
        }
        return removed;
    }

    public void clear() {
        entries.clear();
        synchronized (lru) {
            lru.clear();
        }
        bytes.set(0);
        store.save(Map.of());
    }

    /** Drops expired entries and returns how many were removed. */
    public int clearExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, CacheEntry> entry : new ArrayList<>(entries.entrySet())) {
            if (entry.getValue().expired(now, ttlSeconds)) {
                remove(entry.getKey());
                expired.incrementAndGet();
                removed++;
            }
        }
        return removed;
    }

    private void remove(String key) {
        CacheEntry removed = entries.remove(key);
        if (removed != null) {
            bytes.addAndGet(-removed.rawLength());
            synchronized (lru) {
                lru.remove(key);
            }
        }
    }

    private void evictIfNeeded(long now) {
        if (entries.size() > maxEntries || bytes.get() > maxBytes) {
            clearExpired();
        }
        while ((entries.size() > maxEntries || bytes.get() > maxBytes)) {
            String victim;
            synchronized (lru) {
                victim = lru.pollFirst();
            }
            if (victim == null) {
                break;
            }
            CacheEntry removed = entries.remove(victim);
            if (removed != null) {
                bytes.addAndGet(-removed.rawLength());
                evictions.incrementAndGet();
            }
        }
    }

    /** Writes the current contents through to the backing store. */
    public void flush() {
        store.save(new HashMap<>(entries));
    }

    public int size() {
        return entries.size();
    }

    public long bytes() {
        return bytes.get();
    }

    public Collection<CacheEntry> entries() {
        return List.copyOf(entries.values());
    }

    /** Entries ordered by recency, newest last. Used by {@code /linggango-net cache}. */
    public List<CacheEntry> byRecency() {
        List<CacheEntry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingLong(CacheEntry::lastAccessMillis));
        return list;
    }

    public Stats stats() {
        return new Stats(entries.size(), bytes.get(), hits.get(), misses.get(),
                evictions.get(), expired.get(), rejected.get(), maxEntries, maxBytes);
    }

    public record Stats(int entries,
                        long bytes,
                        long hits,
                        long misses,
                        long evictions,
                        long expired,
                        long rejected,
                        int maxEntries,
                        long maxBytes) {

        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
