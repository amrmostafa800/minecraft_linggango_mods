package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * JEI / EMI and the recipe-viewing ecosystem.
 *
 * <p>Recipe data already exists on every client, because every client runs the same mods.
 * Linggango ships recipe dumps and a recipe-viewing stack (JEI, recipeessentials,
 * fastjei). None of this needs a server round trip, and none of it can grant an item.
 *
 * <p>This is the clearest Category 3 case in the pack and the reason the mod ships with
 * {@code local_recipes} and {@code gui_preferences} already at CLIENT_LOCAL.
 */
public final class JeiEmiAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "jei_emi";
    }

    @Override
    public String displayName() {
        return "JEI / EMI / recipe viewers";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("jei", "emi", "recipeessentials", "fastjei", "guideme", "ponderjs");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("jei", "local_recipes", SyncPolicy.CLIENT_LOCAL,
                        "Recipes are derived from the installed mods, which every client has. JEI never asks "
                                + "the server for them."),
                AdapterRule.safe("jei", "gui_preferences", SyncPolicy.CLIENT_LOCAL,
                        "Search history, favourite items and category ordering are personal and never affect gameplay."),
                AdapterRule.safe("jei", "gui_state", SyncPolicy.CLIENT_LOCAL,
                        "Whether the overlay is open, pinned or hidden is local UI state."),
                AdapterRule.safe("guideme", "gui_state", SyncPolicy.CLIENT_LOCAL,
                        "Guide book navigation is local reading state."),
                AdapterRule.refused("recipeessentials", "server_recipes",
                        "If a pack restricts recipes server-side, the restriction must be enforced on the server, "
                                + "not merely hidden by the client's viewer."));
    }
}
