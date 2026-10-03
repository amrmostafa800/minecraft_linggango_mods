package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * The reference adapter, and the only one whose rules are not mod-specific.
 *
 * <p>Everything here is already handled by the server/client split in vanilla. The adapter
 * exists to document that this mod does not touch it, and to refuse the optimisations that
 * are tempting but wrong.
 */
public final class VanillaAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "vanilla";
    }

    @Override
    public String displayName() {
        return "Minecraft (vanilla)";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("minecraft");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("minecraft", "player_state", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Position, rotation, health, hunger and effects decide every combat and interaction outcome."),
                AdapterRule.safe("minecraft", "inventories", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Item counts and contents are authoritative; a client-local copy would let a player mint items."),
                AdapterRule.safe("minecraft", "containers", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Chests, hoppers and furnace contents are read by other players and by automation."),
                AdapterRule.safe("minecraft", "world_blocks", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Block changes must be seen by everyone or builds desynchronise."),
                AdapterRule.safe("minecraft", "block_entities", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Signs, spawners and beacons carry state other players observe."),
                AdapterRule.safe("minecraft", "mobs", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Mob health, AI and position are shared combat state."),
                AdapterRule.safe("minecraft", "projectiles", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Hit registration depends on the server's projectile state."),
                AdapterRule.safe("minecraft", "redstone", SyncPolicy.SERVER_AUTHORITATIVE,
                        "Redstone updates trigger world changes on the server."),
                AdapterRule.safe("minecraft", "waypoints", SyncPolicy.CLIENT_LOCAL,
                        "A waypoint a player draws is presentation. Vanilla has no shared waypoint concept, so "
                                + "keeping it local cannot change a server outcome."),
                AdapterRule.safe("minecraft", "particles", SyncPolicy.CLIENT_LOCAL,
                        "Particle density and choice are purely visual."),
                AdapterRule.refused("minecraft", "xp",
                        "XP unlocks server-side progression and is spent on anvils and enchanting, so it stays authoritative."),
                AdapterRule.refused("minecraft", "damage",
                        "Damage is the canonical example of state that must never be client-trusted."));
    }
}
