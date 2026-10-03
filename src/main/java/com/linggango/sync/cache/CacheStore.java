package com.linggango.sync.cache;

import java.util.Map;

/**
 * Durable backing store for {@link ClientCache}.
 *
 * <p>Split out so the cache logic can be tested without touching the filesystem, and so a
 * server-side deployment can use a no-op implementation.
 */
public interface CacheStore {

    /** Returns previously persisted entries, or an empty map. Must never throw. */
    Map<String, CacheEntry> load();

    /** Persists the given entries atomically. Must never throw. */
    void save(Map<String, CacheEntry> entries);

    CacheStore NOOP = new CacheStore() {
        @Override
        public Map<String, CacheEntry> load() {
            return Map.of();
        }

        @Override
        public void save(Map<String, CacheEntry> entries) {
            // intentionally empty
        }
    };
}
