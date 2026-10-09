package com.linggango.sync.api;

import com.linggango.sync.config.SyncPolicy;

import java.util.Objects;

/**
 * A registered data type.
 *
 * <p>{@code serializer} is optional. A descriptor may be a pure <em>declaration</em>: an
 * adapter stating which policy a data type should have, before it has implemented a codec
 * for it. Such a descriptor is not wire-ready, and {@link #wireReady()} reports that. The
 * requirement for a serializer is enforced where it matters, at the point a value is
 * actually about to be encoded, rather than at registration, so a missing codec degrades to
 * "this data type is not optimised" instead of crashing the server on startup.
 */
public record SyncDescriptor(String namespace,
                             String dataType,
                             SyncPolicy policy,
                             SyncSerializer serializer,
                             SyncValidator validator) {

    public SyncDescriptor {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(dataType, "dataType");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(validator, "validator");
    }

    /** {@code true} when this descriptor can actually be put on the wire or into a cache. */
    public boolean wireReady() {
        return !policy.isServerAuthoritative() ? serializer != null : true;
    }

    public String key() {
        return SyncRegistry.key(namespace, dataType);
    }
}
