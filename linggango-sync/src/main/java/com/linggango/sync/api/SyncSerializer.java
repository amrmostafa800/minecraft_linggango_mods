package com.linggango.sync.api;

/**
 * Serialises a value to and from a compact byte form for the wire and the client cache.
 *
 * <p>Implementations must be stateless and thread-safe: they are called from the netty
 * event loop (decode) and from server tick threads (encode).
 *
 * @param <T> the value type
 */
public interface SyncSerializer<T> {

    byte[] encode(T value);

    T decode(byte[] data);

    /** Cheap discriminator for logging and for detecting a corrupt cache entry. */
    default String formatId() {
        return getClass().getSimpleName();
    }
}
