package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Mekanism 10.4.16 and its addons (Generators, Tools, Weapons, Applied Mekanistics,
 * Evolved Mekanism).
 *
 * <p>Mekanism machines publish energy, fluid, gas and progress every tick. The values
 * themselves are authoritative and must not be invented by a client. What is safe is
 * coalescing: sending only the newest value when several changes occur between two client
 * ticks, and sending nothing at all when the value is unchanged. The server remains the
 * only writer, so this cannot be used to fake an output.
 */
public final class MekanismAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "mekanism";
    }

    @Override
    public String displayName() {
        return "Mekanism";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("mekanism", "mekanismgenerators", "mekanismtools", "mekanismweapons", "evolvedmekanism");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("mekanism", "energy_networks",
                        SyncPolicy.DELTA_SYNC,
                        "Energy is a monotonic per-tick value where only the newest sample matters. Sending the "
                                + "latest value instead of every intermediate one is safe; the server still owns the number."),
                AdapterRule.safe("mekanism", "fluid_networks",
                        SyncPolicy.DELTA_SYNC,
                        "Same reasoning as energy: the client renders a level, it does not decide it."),
                AdapterRule.safe("mekanism", "machine_progress",
                        SyncPolicy.DELTA_SYNC,
                        "Progress bars are a projection of a server-side counter. Coalescing intermediate ticks "
                                + "cannot complete a craft early."),
                AdapterRule.safe("mekanism", "machine_inventory",
                        SyncPolicy.SERVER_AUTHORITATIVE,
                        "Machine slots hold real items. Never deferred, never client-written."),
                AdapterRule.refused("mekanism", "fission_reactor",
                        "Reactor state can destroy the surrounding area. It stays fully authoritative and unsynchronised in aggregate."));
    }
}
