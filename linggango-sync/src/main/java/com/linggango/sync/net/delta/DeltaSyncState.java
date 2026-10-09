package com.linggango.sync.net.delta;

import java.util.Arrays;

/**
 * Server-side bookkeeping for one delta-synchronised object.
 *
 * <p>The state tracks two snapshots: what the client is believed to hold ({@code lastSent})
 * and the newest server truth ({@code pending}). Updates are recorded, not transmitted.
 * A flush produces a delta from {@code lastSent} to {@code pending} and is rate limited by
 * {@code coalesceMillis}, which is how "update 1, 2, 3, 4" becomes "update 4" without
 * losing any field that the schema marks as additive.
 *
 * <p>Instances are not thread safe; the server owns them and mutates them on its own thread.
 */
public final class DeltaSyncState {

    private final DeltaSchema schema;
    private final int coalesceMillis;

    private Object[] lastSent;
    private Object[] pending;
    private int version;
    private long lastSendMillis;
    private long packetsSent;
    private long bytesSent;
    private long fieldsCoalesced;

    public DeltaSyncState(DeltaSchema schema, int coalesceMillis) {
        this.schema = schema;
        this.coalesceMillis = Math.max(0, coalesceMillis);
    }

    public DeltaSchema schema() {
        return schema;
    }

    public int version() {
        return version;
    }

    /** Records new server truth. Overwrites any pending snapshot, which is the coalescing step. */
    public void update(Object[] current) {
        if (pending != null && pending.length == current.length) {
            int changed = 0;
            for (int i = 0; i < current.length; i++) {
                if (!DeltaCodec.valueEquals(schema.field(i).type(), pending[i], current[i])) {
                    changed++;
                }
            }
            if (changed > 0) {
                fieldsCoalesced += changed;
            }
        }
        this.pending = Arrays.copyOf(current, current.length);
    }

    public boolean hasPendingChange() {
        if (pending == null) {
            return false;
        }
        if (lastSent == null) {
            return true;
        }
        for (int i = 0; i < schema.size(); i++) {
            if (!DeltaCodec.valueEquals(schema.field(i).type(), lastSent[i], pending[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Produces the payload to send now, or {@code null} when there is nothing to send or the
     * coalescing window has not elapsed.
     */
    public byte[] flushIfDue(long nowMillis) {
        if (!hasPendingChange()) {
            return null;
        }
        if (coalesceMillis > 0 && lastSent != null && nowMillis - lastSendMillis < coalesceMillis) {
            return null;
        }
        return flush(nowMillis);
    }

    /** Produces a payload immediately, ignoring the coalescing window. */
    public byte[] flush() {
        return flush(System.currentTimeMillis());
    }

    /** Produces a payload immediately against the supplied clock. */
    public byte[] flush(long nowMillis) {
        if (pending == null) {
            return null;
        }
        byte[] payload = DeltaCodec.encode(schema, version, version + 1, lastSent, pending);
        if (payload.length == 0) {
            return null;
        }
        lastSent = Arrays.copyOf(pending, pending.length);
        version++;
        lastSendMillis = nowMillis;
        packetsSent++;
        bytesSent += payload.length;
        return payload;
    }

    /** Convenience for tests and one-shot senders: record then send immediately. */
    public byte[] flushAfterUpdate(Object[] current) {
        update(current);
        return flush();
    }

    /** Forces the next flush to be a full snapshot, used when a client (re)joins. */
    public void resetBaseline() {
        lastSent = null;
    }

    public long packetsSent() {
        return packetsSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long fieldsCoalesced() {
        return fieldsCoalesced;
    }
}
