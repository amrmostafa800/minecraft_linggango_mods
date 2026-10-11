package com.linggango.sync.net;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether an outbound custom payload is an immediate repeat of the last payload
 * sent to the same connection on the same channel.
 *
 * <p>State is keyed by (connection, channel). For each key the last payload's message
 * index and bytes are remembered; a new payload is a duplicate when both are equal. A
 * differing payload overwrites the remembered one, so only back-to-back repeats are
 * suppressed — an A, B, A, B sequence is never touched. Bytes are compared exactly rather
 * than by hash, because a hash collision here would silently drop a packet the client
 * never received.
 *
 * <p>Minecraft types are deliberately absent so this can be unit-tested without a launch.
 * The connection argument is used only as an opaque identity key; callers pass the
 * {@code Connection} object and must call {@link #forget} when it closes.
 */
public final class DuplicateSuppressor {

    /** Safety net so a missed {@code forget} cannot grow the map without bound. */
    private static final int MAX_CONNECTIONS = 1024;
    private static final int MAX_CHANNELS_PER_CONNECTION = 512;

    private final Map<Object, Map<String, Last>> byConnection = new ConcurrentHashMap<>();

    /**
     * Records the payload and reports whether it repeats the immediately preceding one.
     *
     * @return {@code true} when this payload equals the last payload on the same
     *         connection and channel, and should therefore not be sent.
     */
    public boolean shouldSuppress(Object connection, String channel, int index, byte[] payload) {
        if (connection == null || channel == null || payload == null) {
            return false;
        }
        if (byConnection.size() > MAX_CONNECTIONS) {
            byConnection.clear();
        }
        Map<String, Last> channels = byConnection.computeIfAbsent(connection, c -> new ConcurrentHashMap<>());
        if (channels.size() > MAX_CHANNELS_PER_CONNECTION) {
            channels.clear();
        }
        Last previous = channels.put(channel, new Last(index, payload));
        return previous != null && previous.matches(index, payload);
    }

    /** Drops all state for a closed connection. */
    public void forget(Object connection) {
        if (connection != null) {
            byConnection.remove(connection);
        }
    }

    private record Last(int index, byte[] payload) {
        boolean matches(int index, byte[] payload) {
            return this.index == index && Arrays.equals(this.payload, payload);
        }
    }
}
