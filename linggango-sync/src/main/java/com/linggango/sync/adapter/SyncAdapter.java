package com.linggango.sync.adapter;

import java.util.List;
import java.util.Set;

/**
 * An integration point for one mod (or one family of mods).
 *
 * <p>An adapter does not rewrite packets. It declares which of its mod's data types can be
 * cached, deferred or sent as deltas, and why. The actual decision stays with the
 * operator's config, and the default for everything is
 * {@link com.linggango.sync.config.SyncPolicy#SERVER_AUTHORITATIVE}.
 *
 * <p>An adapter that cannot establish that an optimisation is safe must declare
 * {@link AdapterRule#refused} rather than staying silent, so the decision is documented.
 */
public interface SyncAdapter {

    /** Stable id, used in logs and reports. */
    String id();

    /** Human-readable name. */
    String displayName();

    /** Mod ids whose traffic this adapter understands. */
    Set<String> namespaces();

    /** Every data type this adapter has an opinion about. */
    List<AdapterRule> rules();

    /**
     * Whether the mod this adapter targets is present in this instance. Adapters whose
     * target is absent are skipped, so a client and server with different mod sets both
     * start cleanly.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * The category used to look up the operator's override for a rule. By default this is
     * the rule's data type, which is the same key used in {@code sync.<category>}.
     */
    default String categoryOf(AdapterRule rule) {
        return rule.dataType();
    }
}
