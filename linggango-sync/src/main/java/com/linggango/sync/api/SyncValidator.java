package com.linggango.sync.api;

/**
 * Server-side gate for any value a client wants to write back.
 *
 * <p>Client-local storage is only acceptable when cheating the local copy cannot produce
 * an unauthorised server-side result. Every path that accepts client input must run it
 * through a validator first; the default implementation rejects everything.
 */
@FunctionalInterface
public interface SyncValidator {

    /**
     * @param context free-form context (player id, target object, category) for logging
     * @param data    the client-supplied bytes, already length-checked
     * @return {@code true} to accept
     */
    boolean validate(String context, byte[] data);

    static SyncValidator rejectAll() {
        return (context, data) -> false;
    }

    static SyncValidator acceptAll() {
        return (context, data) -> true;
    }

    /** Accepts only if every delegate accepts. */
    static SyncValidator allOf(SyncValidator... validators) {
        return (context, data) -> {
            for (SyncValidator validator : validators) {
                if (!validator.validate(context, data)) {
                    return false;
                }
            }
            return true;
        };
    }

    /** Rejects payloads above {@code maxBytes}, then delegates. */
    static SyncValidator maxSize(int maxBytes, SyncValidator delegate) {
        return (context, data) -> data != null && data.length <= maxBytes && delegate.validate(context, data);
    }
}
