package com.linggango.sync.config;

import java.util.Locale;

/**
 * How a piece of data is allowed to move between server and client.
 *
 * <p>The default is {@link #SERVER_AUTHORITATIVE}. Anything the mod cannot positively
 * classify stays server-authoritative, which is the fail-closed behaviour required
 * for mod compatibility.
 */
public enum SyncPolicy {

    /**
     * The server owns the value and synchronises it normally. Never make this
     * client-authoritative: cheating the client copy would change a server-side outcome.
     */
    SERVER_AUTHORITATIVE,

    /**
     * The server still owns the value, but it is only pushed when a client actually
     * needs it (join, GUI open, area entry, explicit request) rather than continuously.
     * The client keeps a versioned cache so unchanged payloads are not resent.
     */
    ON_DEMAND,

    /**
     * The value is only meaningful on the client. It is stored in the client's own
     * config/data directory and is never synchronised.
     */
    CLIENT_LOCAL,

    /**
     * Server-authoritative, but transmitted as field-level deltas against the last
     * acknowledged version instead of whole-object payloads.
     */
    DELTA_SYNC,

    /**
     * Server-authoritative with a content hash/version so identical payloads are
     * skipped entirely. A stricter form of {@link #ON_DEMAND} for large NBT blobs.
     */
    CACHEABLE;

    public boolean isServerAuthoritative() {
        return this == SERVER_AUTHORITATIVE || this == ON_DEMAND || this == DELTA_SYNC || this == CACHEABLE;
    }

    public boolean allowsClientStorage() {
        return this == CLIENT_LOCAL || this == ON_DEMAND || this == CACHEABLE;
    }

    /** Returns the policy for {@code raw}, or {@link #SERVER_AUTHORITATIVE} if unknown. */
    public static SyncPolicy parse(String raw) {
        if (raw == null) {
            return SERVER_AUTHORITATIVE;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (SyncPolicy policy : values()) {
            if (policy.name().equals(normalised)) {
                return policy;
            }
        }
        return SERVER_AUTHORITATIVE;
    }
}
