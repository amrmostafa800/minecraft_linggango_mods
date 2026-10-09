package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Applied Energistics 2 and its addons (ExtendedAE, MEGA Cells, AE2WTLib, AE2 Things,
 * Applied Mekanistics, Applied Flux, ME Requester, AE2-CT).
 *
 * <p>The ME network's stored items, fluids and chemical amounts are server state: they
 * decide what a crafting job consumes and what a player receives. They stay authoritative.
 *
 * <p>What is safe to defer is the <em>browsing</em> view of that network. An ME terminal
 * GUI asks for a list of what is available and how much; that is a read-only projection of
 * authoritative state. Sending it on GUI open, keyed by a version hash, removes the
 * continuous resync without changing what any player can extract.
 */
public final class AE2Adapter implements SyncAdapter {

    @Override
    public String id() {
        return "ae2";
    }

    @Override
    public String displayName() {
        return "Applied Energistics 2";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("ae2", "appliedenergistics2", "extendedae", "megacells", "ae2wtlib",
                "ae2things", "appmek", "appflux", "merequester", "ae2ct", "ae2insertexportcard");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("ae2", "network_browsing",
                        SyncPolicy.ON_DEMAND,
                        "The terminal item list is a read-only projection. Refreshing it on GUI open, and only "
                                + "when the network version changed, cannot grant an item the server would refuse."),
                AdapterRule.safe("ae2", "network_state",
                        SyncPolicy.DELTA_SYNC,
                        "Energy and channel counts change every tick but only the latest value matters, so "
                                + "coalescing to the newest sample is safe. Item and fluid amounts are excluded: they are authoritative."),
                AdapterRule.refused("ae2", "storage_contents",
                        "Stored item and fluid amounts decide what a crafting job consumes and what a player can extract."),
                AdapterRule.refused("ae2", "crafting_cpu",
                        "Crafting job progress and the resulting items must be server-decided."),
                AdapterRule.refused("ae2", "grid_energy",
                        "Powering a network off by starving it is a real gameplay action, so energy state stays authoritative; "
                                + "only its transmission rate is coalesced."));
    }
}
