package com.linggango.sync.cache;

import java.util.Arrays;

/**
 * One cached server-owned value, addressed by {@code key} and identified by
 * {@code version} plus a content {@code hash}.
 *
 * <p>The hash exists so a client can prove it already holds a payload without the server
 * having to trust the client's version counter. A version mismatch with a hash match is
 * harmless (the data is the same); a hash mismatch always forces a resend.
 */
public final class CacheEntry {

    private final String key;
    private final long version;
    private final String hash;
    private final byte[] data;
    private final long createdAtMillis;
    private volatile long lastAccessMillis;

    public CacheEntry(String key, long version, String hash, byte[] data, long createdAtMillis) {
        this.key = key;
        this.version = version;
        this.hash = hash;
        this.data = data;
        this.createdAtMillis = createdAtMillis;
        this.lastAccessMillis = createdAtMillis;
    }

    public String key() {
        return key;
    }

    public long version() {
        return version;
    }

    public String hash() {
        return hash;
    }

    /** Defensive copy: callers must not be able to mutate the cached bytes. */
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }

    /** Length without the defensive copy, for accounting and reporting. */
    public int rawLength() {
        return data.length;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long lastAccessMillis() {
        return lastAccessMillis;
    }

    void touch(long nowMillis) {
        this.lastAccessMillis = nowMillis;
    }

    public boolean expired(long nowMillis, long ttlSeconds) {
        return ttlSeconds > 0 && nowMillis - createdAtMillis > ttlSeconds * 1000L;
    }

    /** True when this entry can satisfy a request for {@code version}/{@code hash}. */
    public boolean matches(long requestedVersion, String requestedHash) {
        if (requestedHash != null && !requestedHash.isEmpty()) {
            return requestedHash.equals(hash);
        }
        return requestedVersion == version;
    }

    @Override
    public String toString() {
        return "CacheEntry[" + key + " v" + version + " " + hash + " " + data.length + "B]";
    }
}
