package com.linggango.sync.adapter.impl;

import com.linggango.sync.adapter.AdapterRule;
import com.linggango.sync.adapter.SyncAdapter;
import com.linggango.sync.config.SyncPolicy;

import java.util.List;
import java.util.Set;

/**
 * Ars Nouveau 4.12.7 and its addons (ars_additions, ars_elemental, ars_creo, arseng,
 * not_enough_glyphs, toomanyglyphs, adamsarsplus, somakespells, gtbcs_spell_lib).
 *
 * <p>Phase 1 found 51 packet classes here. Most are gameplay effects that other players
 * must see. The safe subset is the spellbook and glyph-crafting UI, which renders from data
 * already present in the mod's own files.
 */
public final class ArsNouveauAdapter implements SyncAdapter {

    @Override
    public String id() {
        return "ars_nouveau";
    }

    @Override
    public String displayName() {
        return "Ars Nouveau";
    }

    @Override
    public Set<String> namespaces() {
        return Set.of("ars_nouveau", "arsnouveau", "ars_additions", "ars_elemental", "ars_creo",
                "arseng", "not_enough_glyphs", "toomanyglyphs", "adamsarsplus", "somakespells",
                "gtbcs_spell_lib", "ars_n_spells", "arsomega");
    }

    @Override
    public List<AdapterRule> rules() {
        return List.of(
                AdapterRule.safe("ars_nouveau", "gui_data",
                        SyncPolicy.ON_DEMAND,
                        "The spellbook and glyph-crafting screens render glyph definitions that ship with the mod. "
                                + "The server never needs to stream them per tick."),
                AdapterRule.refused("ars_nouveau", "spell_effects",
                        "PacketANEffect, PacketOneShotAnimation and the rewind/delay packets are shared combat "
                                + "presentation; other players must see the same spell land."),
                AdapterRule.refused("ars_nouveau", "mana",
                        "Mana gates casting. A client-local mana value would let a player cast without the server "
                                + "agreeing, so it stays authoritative."),
                AdapterRule.refused("ars_nouveau", "familiar_state",
                        "Familiars are real entities with server-side behaviour."),
                AdapterRule.refused("ars_nouveau", "persistent_data",
                        "PacketGetPersistentData is an explicit data request; answering it early or from cache "
                                + "would change progression state."));
    }
}
