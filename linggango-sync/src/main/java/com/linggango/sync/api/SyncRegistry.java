package com.linggango.sync.api;

import com.linggango.sync.config.SyncPolicy;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registration point for third-party integrations.
 *
 * <p>A mod (or a scripting bridge, or an operator-authored adapter) registers the data
 * types it wants this system to understand. Nothing is optimised implicitly: if a data
 * type is not registered, or its {@link SyncPolicy} is
 * {@link SyncPolicy#SERVER_AUTHORITATIVE}, behaviour is exactly the vanilla/mod behaviour.
 *
 * <p>Registration is keyed by {@code namespace:dataType}. Re-registering the same key
 * replaces the previous entry and is logged, so a config-driven override can win over a
 * hard-coded adapter.
 */
public final class SyncRegistry {

    private static final ConcurrentMap<String, SyncDescriptor> DESCRIPTORS = new ConcurrentHashMap<>();

    private SyncRegistry() {
    }

    public static String key(String namespace, String dataType) {
        return Objects.requireNonNull(namespace, "namespace") + ':' + Objects.requireNonNull(dataType, "dataType");
    }

    /**
     * Registers a data type.
     *
     * @param namespace  the owning mod id, e.g. {@code ae2}
     * @param dataType   a stable identifier within that namespace, e.g. {@code network_browsing}
     * @param policy     the requested policy. Anything other than
     *                   {@link SyncPolicy#SERVER_AUTHORITATIVE} is an explicit claim that
     *                   deferring or localising this data cannot change a server outcome.
     * @param serializer serialises the value for the wire/cache. May be {@code null} for
     *                   {@link SyncPolicy#CLIENT_LOCAL} (which never touches the wire) or
     *                   while a data type is still only being declared. A descriptor with a
     *                   wire-visible policy and no serializer is not
     *                   {@link SyncDescriptor#wireReady()} and is never optimised.
     * @param validator  runs server-side before a client-supplied value is trusted. Use
     *                   {@link SyncValidator#rejectAll()} when the client never sends this.
     * @return the stored descriptor.
     */
    public static SyncDescriptor register(String namespace,
                                          String dataType,
                                          SyncPolicy policy,
                                          SyncSerializer serializer,
                                          SyncValidator validator) {
        SyncDescriptor descriptor = new SyncDescriptor(namespace, dataType, policy, serializer, validator);
        SyncDescriptor previous = DESCRIPTORS.put(key(namespace, dataType), descriptor);
        if (previous != null) {
            com.linggango.sync.LinggangoSync.LOGGER.warn(
                    "Sync descriptor {}:{} re-registered ({} -> {})",
                    namespace, dataType, previous.policy(), policy);
        }
        if (policy.isServerAuthoritative() && serializer == null) {
            // A declaration without a codec. This is legal but cannot be used, and saying so
            // loudly at registration is what stops a half-finished adapter from looking enabled.
            com.linggango.sync.LinggangoSync.LOGGER.debug(
                    "Sync descriptor {}:{} declares {} but has no serializer yet; it will not be optimised",
                    namespace, dataType, policy);
        }
        return descriptor;
    }

    public static SyncDescriptor register(String namespace, String dataType, SyncPolicy policy, SyncSerializer serializer) {
        return register(namespace, dataType, policy, serializer, SyncValidator.rejectAll());
    }

    public static Optional<SyncDescriptor> lookup(String namespace, String dataType) {
        return Optional.ofNullable(DESCRIPTORS.get(key(namespace, dataType)));
    }

    public static java.util.Map<String, SyncDescriptor> snapshot() {
        return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(DESCRIPTORS));
    }

    public static int size() {
        return DESCRIPTORS.size();
    }

    public static void clear() {
        DESCRIPTORS.clear();
    }
}
