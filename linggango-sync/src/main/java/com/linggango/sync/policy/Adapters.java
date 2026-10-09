package com.linggango.sync.policy;

import com.linggango.sync.LinggangoSync;
import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.adapter.impl.AE2Adapter;
import com.linggango.sync.adapter.impl.ArsNouveauAdapter;
import com.linggango.sync.adapter.impl.CreateAdapter;
import com.linggango.sync.adapter.impl.GenericModAdapter;
import com.linggango.sync.adapter.impl.JeiEmiAdapter;
import com.linggango.sync.adapter.impl.MekanismAdapter;
import com.linggango.sync.adapter.impl.SophisticatedStorageAdapter;
import com.linggango.sync.adapter.impl.TomsStorageAdapter;
import com.linggango.sync.adapter.impl.VanillaAdapter;
import com.linggango.sync.adapter.impl.XaeroAdapter;
import com.linggango.sync.api.SyncRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the built-in adapters and mirrors their rules into {@link SyncRegistry}.
 *
 * <p>Registration is intentionally non-destructive: a rule whose default is anything other
 * than {@code SERVER_AUTHORITATIVE} is recorded, but the effective policy still comes from
 * the operator's config via {@link DataClassifier#resolve}. This keeps the shipped defaults
 * safe while making every available optimisation discoverable and auditable.
 */
public final class Adapters {

    private static final List<SyncAdapter> ADAPTERS = new ArrayList<>();
    private static volatile boolean registered;

    private Adapters() {
    }

    public static synchronized void registerDefaults() {
        if (!registered) {
            add(new VanillaAdapter());
            add(new CreateAdapter());
            add(new AE2Adapter());
            add(new ArsNouveauAdapter());
            add(new MekanismAdapter());
            add(new SophisticatedStorageAdapter());
            add(new TomsStorageAdapter());
            add(new JeiEmiAdapter());
            add(new XaeroAdapter());
            add(new GenericModAdapter());
            registered = true;
        }
        mirror();
    }

    /**
     * Copies every adapter rule into {@link SyncRegistry}.
     *
     * <p>Deliberately re-runnable. Adding adapters is a once-only operation, but mirroring is
     * not, so a registry that was cleared (a reload, a test) cannot silently end up empty
     * while the adapters still believe they are registered.
     */
    public static synchronized int mirror() {
        int total = 0;
        for (SyncAdapter adapter : ADAPTERS) {
            if (!adapter.isAvailable()) {
                continue;
            }
            for (AdapterRule rule : adapter.rules()) {
                SyncRegistry.register(rule.namespace(), rule.dataType(), rule.defaultPolicy(), null);
                total++;
            }
        }
        LinggangoSync.LOGGER.info("Registered {} adapters declaring {} data types", ADAPTERS.size(), total);
        return total;
    }

    public static void add(SyncAdapter adapter) {
        ADAPTERS.add(adapter);
    }

    public static List<SyncAdapter> all() {
        return Collections.unmodifiableList(ADAPTERS);
    }

    /** Flattens every adapter rule into namespace -> dataType -> policy, for reporting. */
    public static Map<String, Map<String, AdapterRule>> rulesByNamespace() {
        Map<String, Map<String, AdapterRule>> out = new LinkedHashMap<>();
        for (SyncAdapter adapter : ADAPTERS) {
            for (AdapterRule rule : adapter.rules()) {
                out.computeIfAbsent(rule.namespace(), k -> new LinkedHashMap<>()).put(rule.dataType(), rule);
            }
        }
        return out;
    }
}
