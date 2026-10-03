package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Create 6.0.8 (plus createaddition, create_connected, create_new_age and friends).
 *
 * <p>Phase 1 found Create to be one of the two heaviest custom-packet sources in Linggango
 * (112 packet classes). Every one of them carries simulation state that the server resolves:
 * contraption motion, mounted storage, train signalling, glue and elevators. Coalescing or
 * deferring any of it changes what players see happen, so this adapter refuses all of it.
 *
 * <p>The one thing that is genuinely deferrable is the Ponder scene index, which is
 * instructional content identical on every client.
 */
public final class CreateAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "create";
    }

    @Override
    public String displayName() {
        return "Create";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("create", "createaddition", "create_connected", "create_new_age", "createoreexcavation");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.refused("create", "contraptions",
                        "ContraptionBlockChanged/Disassembly/Stall/Relocation packets describe shared motion and "
                                + "collision. Dropping intermediate states changes physics and player collisions."),
                AdapterRule.refused("create", "contraption_storage",
                        "MountedStorageSyncPacket carries real inventory contents that other players can extract from."),
                AdapterRule.refused("create", "trains",
                        "Train signalling and collision packets are ordered; reordering desynchronises train schedules."),
                AdapterRule.refused("create", "glue",
                        "Super glue selection and removal are edit operations on the world."),
                AdapterRule.refused("create", "elevators",
                        "Floor lists and target floors are interactive machine state."),
                AdapterRule.refused("create", "kinetic_network",
                        "Rotational speed and stress are read by players and by other machines."),
                AdapterRule.safe("create", "ponder",
                        SyncPolicy.ON_DEMAND,
                        "Ponder scenes are static instructional data shipped in the mod. The server never "
                                + "simulates them, so a client can load them from its own install."));
    }
}
