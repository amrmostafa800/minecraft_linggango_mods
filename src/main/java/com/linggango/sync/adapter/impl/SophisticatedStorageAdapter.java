package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Sophisticated Backpacks / Storage / Core 1.5.2.
 *
 * <p>Backpack contents are inventory and stay authoritative. The upgrade-slot settings and
 * the storage GUI layout are per-viewer presentation, so they can be deferred until the
 * player opens the screen.
 */
public final class SophisticatedStorageAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "sophisticated";
    }

    @Override
    public String displayName() {
        return "Sophisticated Storage / Backpacks";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("sophisticatedcore", "sophisticatedbackpacks", "sophisticatedstorage");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("sophisticatedcore", "gui_data",
                        SyncPolicy.ON_DEMAND,
                        "The storage screen's layout and upgrade panel render from the mod's own definitions. "
                                + "Pushing them on open, keyed by version, removes a per-tick resync."),
                AdapterRule.refused("sophisticatedcore", "storage_contents",
                        "Slots hold real items and upgrades with real effects."),
                AdapterRule.refused("sophisticatedcore", "upgrade_effects",
                        "Upgrades change how the block behaves on the server, so their state is authoritative."));
    }
}
