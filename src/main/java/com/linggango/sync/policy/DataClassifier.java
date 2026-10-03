package com.linggango.sync.policy;

import com.linggango.sync.config.SyncPolicy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The out-of-the-box classification of Linggango's data, derived from the Phase 1
 * compatibility report (see {@code docs/COMPATIBILITY.md}).
 *
 * <p>Category names are the stable keys used in {@code sync.<category>} config entries.
 * Per-mod overrides use {@code mods.<namespace>.<category>}.
 *
 * <p>Every default here is chosen so that breaking multiplayer correctness requires an
 * explicit operator decision. The only categories defaulting away from
 * {@link SyncPolicy#SERVER_AUTHORITATIVE} are ones that provably never influence a
 * server-side outcome.
 */
public final class DataClassifier {

    private DataClassifier() {
    }

    public static Map<String, SyncPolicy> defaultCategoryPolicies() {
        Map<String, SyncPolicy> map = new LinkedHashMap<>();

        // --- Category 1: must sync. Gameplay outcomes depend on these. ---
        map.put("player_state", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("inventories", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("containers", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("machines", SyncPolicy.ON_DEMAND);
        map.put("machine_inventory", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("machine_progress", SyncPolicy.DELTA_SYNC);
        map.put("world_blocks", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("block_entities", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("mobs", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("bosses", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("projectiles", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("redstone", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("create_contraptions", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("energy_networks", SyncPolicy.DELTA_SYNC);
        map.put("fluid_networks", SyncPolicy.DELTA_SYNC);
        map.put("progression", SyncPolicy.SERVER_AUTHORITATIVE);

        // --- Category 2: on demand. Server keeps the authority; delivery is deferred. ---
        map.put("gui_data", SyncPolicy.ON_DEMAND);
        map.put("recipe_structures", SyncPolicy.ON_DEMAND);
        map.put("network_browsing", SyncPolicy.ON_DEMAND);
        map.put("nonessential_be", SyncPolicy.CACHEABLE);

        // --- Category 3: client local. Stored in the client's own directory, never sent. ---
        map.put("gui_state", SyncPolicy.CLIENT_LOCAL);
        map.put("gui_preferences", SyncPolicy.CLIENT_LOCAL);
        map.put("keybinds", SyncPolicy.CLIENT_LOCAL);
        map.put("visuals", SyncPolicy.CLIENT_LOCAL);
        map.put("particles", SyncPolicy.CLIENT_LOCAL);
        map.put("cosmetics", SyncPolicy.CLIENT_LOCAL);
        map.put("waypoints", SyncPolicy.CLIENT_LOCAL);
        map.put("map_cache", SyncPolicy.CLIENT_LOCAL);
        map.put("local_recipes", SyncPolicy.CLIENT_LOCAL);
        map.put("hud", SyncPolicy.CLIENT_LOCAL);
        map.put("search_history", SyncPolicy.CLIENT_LOCAL);
        map.put("tooltips", SyncPolicy.CLIENT_LOCAL);

        return map;
    }

    /**
     * Per-mod defaults for namespaces where the Phase 1 report identified a concrete,
     * safe optimisation. Namespaces not listed here inherit the category policy above,
     * which is the conservative behaviour.
     */
    public static Map<String, SyncPolicy> defaultModPolicies() {
        Map<String, SyncPolicy> map = new LinkedHashMap<>();
        // Key format for these is "<namespace>:<category>"; SyncConfig merges them into
        // the nested mods.<namespace>.<category> shape on write.
        map.put("jei", SyncPolicy.CLIENT_LOCAL);
        map.put("emi", SyncPolicy.CLIENT_LOCAL);
        map.put("xaerominimap", SyncPolicy.CLIENT_LOCAL);
        map.put("xaeroworldmap", SyncPolicy.CLIENT_LOCAL);
        map.put("journeymap", SyncPolicy.CLIENT_LOCAL);
        map.put("ae2", SyncPolicy.ON_DEMAND);
        map.put("appliedenergistics2", SyncPolicy.ON_DEMAND);
        map.put("extendedae", SyncPolicy.ON_DEMAND);
        map.put("megacells", SyncPolicy.ON_DEMAND);
        map.put("create", SyncPolicy.SERVER_AUTHORITATIVE);
        map.put("mekanism", SyncPolicy.DELTA_SYNC);
        map.put("thermal", SyncPolicy.DELTA_SYNC);
        map.put("ars_nouveau", SyncPolicy.ON_DEMAND);
        map.put("sophisticatedcore", SyncPolicy.ON_DEMAND);
        map.put("sophisticatedbackpacks", SyncPolicy.ON_DEMAND);
        map.put("sophisticatedstorage", SyncPolicy.ON_DEMAND);
        map.put("toms_storage", SyncPolicy.ON_DEMAND);
        map.put("storagedrawers", SyncPolicy.ON_DEMAND);
        map.put("refinedstorage", SyncPolicy.ON_DEMAND);
        return map;
    }

    /** Resolves the effective policy using the loaded config. */
    public static SyncPolicy resolve(String namespace, String category) {
        com.linggango.sync.config.SyncConfig config = com.linggango.sync.config.SyncConfig.get();
        return resolve(config.categories(), config.modDefaults(), config.modOverrides(), namespace, category);
    }

    /** Resolves the effective policy for a namespace/category pair. */
    public static SyncPolicy resolve(Map<String, SyncPolicy> categories,
                                     Map<String, SyncPolicy> modDefaults,
                                     Map<String, Map<String, SyncPolicy>> modOverrides,
                                     String namespace,
                                     String category) {
        String normalised = namespace == null ? null : normaliseNamespace(namespace);
        if (normalised != null) {
            Map<String, SyncPolicy> overrides = modOverrides.get(normalised);
            if (overrides != null) {
                SyncPolicy exact = overrides.get(category);
                if (exact != null) {
                    return exact;
                }
                SyncPolicy wildcard = overrides.get("*");
                if (wildcard != null) {
                    return wildcard;
                }
            }
        }
        SyncPolicy categoryPolicy = categories.get(category);
        if (categoryPolicy != null) {
            return categoryPolicy;
        }
        if (normalised != null) {
            SyncPolicy modDefault = modDefaults.get(normalised);
            if (modDefault != null) {
                return modDefault;
            }
        }
        return SyncPolicy.SERVER_AUTHORITATIVE;
    }

    public static String normaliseNamespace(String raw) {
        return raw == null ? "unknown" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
