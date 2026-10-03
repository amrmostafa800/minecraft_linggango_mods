package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * The fail-closed default for every mod without a dedicated adapter.
 *
 * <p>Linggango installs roughly 400 mods. Writing a bespoke adapter for each would be
 * unbounded work and, worse, an unbounded source of correctness bugs. This adapter instead
 * encodes the rule the whole system is built on: <em>if we cannot prove an optimisation is
 * safe, we do not perform it.</em>
 *
 * <p>Concretely, every data type not claimed by another adapter resolves to
 * {@link SyncPolicy#SERVER_AUTHORITATIVE}, and the mod's packets pass through untouched.
 * The categories below are the ones that are safe for <em>any</em> mod, because they
 * describe the viewer rather than the world.
 */
public final class GenericModAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public String displayName() {
        return "Generic fallback (fail-closed)";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("*");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("*", "gui_state", SyncPolicy.CLIENT_LOCAL,
                        "Which screen a player has open, scroll position, sort column and filter text are local to "
                                + "that player and are never read by the server."),
                AdapterRule.safe("*", "gui_preferences", SyncPolicy.CLIENT_LOCAL,
                        "Personal UI preferences. Cheating them changes nothing on the server."),
                AdapterRule.safe("*", "visuals", SyncPolicy.CLIENT_LOCAL,
                        "Render distance, brightness and model toggles are per-viewer."),
                AdapterRule.safe("*", "particles", SyncPolicy.CLIENT_LOCAL,
                        "Particle selection and density are presentation."),
                AdapterRule.safe("*", "cosmetics", SyncPolicy.CLIENT_LOCAL,
                        "Cosmetic appearance carries no gameplay advantage, which is exactly the Category 4 case."),
                AdapterRule.safe("*", "tooltips", SyncPolicy.CLIENT_LOCAL,
                        "Tooltip text is assembled client-side from data the client already has."),
                AdapterRule.safe("*", "keybinds", SyncPolicy.CLIENT_LOCAL,
                        "Key mappings are local input configuration."),
                AdapterRule.refused("*", "unknown",
                        "Anything this system cannot classify synchronises exactly as the mod intended. This is the "
                                + "fail-closed rule that keeps the optimisation from breaking other mods."));
    }
}
