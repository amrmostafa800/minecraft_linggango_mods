package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Xaero's Minimap and World Map, plus the Xaero waystone compatibility layer.
 *
 * <p>Phase 1 confirmed the minimap and world map are client-only in Linggango: the server
 * pack ships no Xaero server component, and the config directory only contains client HUD
 * layout files. The map is rendered from chunks the client already receives.
 *
 * <p>The one nuance is the waystone compatibility layer: waystone <em>destinations</em> are
 * server data (they decide where a player may travel), while the <em>drawing</em> of them on
 * a map is local. The adapter keeps the former authoritative.
 */
public final class XaeroAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "xaero";
    }

    @Override
    public String displayName() {
        return "Xaero's Maps";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("xaerominimap", "xaeroworldmap", "xaeros_waystones_compatibility", "journeymap");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("xaerominimap", "map_cache", SyncPolicy.CLIENT_LOCAL,
                        "Map tiles are rendered from chunks the client already has. Caching them locally is "
                                + "what the mod already does and cannot affect the server."),
                AdapterRule.safe("xaerominimap", "waypoints", SyncPolicy.CLIENT_LOCAL,
                        "Player-created waypoints are personal markers. Sharing them is a separate, explicit feature."),
                AdapterRule.safe("xaeroworldmap", "map_cache", SyncPolicy.CLIENT_LOCAL,
                        "Same as the minimap: a local render cache."),
                AdapterRule.refused("xaeros_waystones_compatibility", "waystone_destinations",
                        "A waystone destination determines where a player may teleport, so the list stays server-authoritative."));
    }
}
