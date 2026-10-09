package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Tom's Simple Storage, Storage Drawers and Refined Storage.
 *
 * <p>All three are "browse a large inventory" GUIs. The browse list is a read-only
 * projection and is the best candidate in the whole pack for on-demand, hash-keyed
 * delivery: it is large, it changes often, and nothing about it grants an item.
 */
public final class TomsStorageAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "storage_browsing";
    }

    @Override
    public String displayName() {
        return "Tom's Storage / Storage Drawers / Refined Storage";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("toms_storage", "storagedrawers", "refinedstorage", "refined_mod");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("toms_storage", "network_browsing",
                        SyncPolicy.ON_DEMAND,
                        "The terminal list is a projection. Refreshing on GUI open, only when the version changed, "
                                + "removes a continuous resync without affecting extraction."),
                AdapterRule.safe("storagedrawers", "network_browsing",
                        SyncPolicy.ON_DEMAND,
                        "Drawer contents are authoritative; only the GUI's browsing view is deferred."),
                AdapterRule.safe("refinedstorage", "network_browsing",
                        SyncPolicy.ON_DEMAND,
                        "Same as AE2: browsing is a read-only view of authoritative storage."),
                AdapterRule.refused("toms_storage", "inventory",
                        "Terminal contents are real items and stay authoritative."),
                AdapterRule.refused("storagedrawers", "inventory",
                        "Drawer contents are real items and stay authoritative."),
                AdapterRule.refused("refinedstorage", "crafting",
                        "Crafting requests consume real items on the server."));
    }
}
