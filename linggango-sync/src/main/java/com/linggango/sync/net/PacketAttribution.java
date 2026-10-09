package com.linggango.sync.net;

/**
 * Maps a packet (or a channel name) to the mod most likely responsible for it.
 *
 * <p>Custom-payload channel names are namespaced, so those are attributed exactly. For
 * vanilla packet classes, attribution is by class-name heuristic: a modded subclass or a
 * packet whose package contains the mod's package root is attributed to that mod. The
 * heuristic is deliberately conservative and only affects reporting, never behaviour.
 */
public final class PacketAttribution {

    /** Channel/package fragments, longest first so more specific entries win. */
    private static final String[][] HINTS = {
            {"ars_nouveau", "Ars Nouveau"},
            {"arsnouveau", "Ars Nouveau"},
            {"notenoughglyphs", "Not Enough Glyphs"},
            {"ars_elemental", "Ars Elemental"},
            {"arseng", "Ars Energistique"},
            {"appliedenergistics", "AE2"},
            {"ae2", "AE2"},
            {"extendedae", "ExtendedAE"},
            {"megacells", "MEGA Cells"},
            {"ae2wtlib", "AE2 Wireless Terminals"},
            {"ae2things", "AE2 Things"},
            {"appmek", "Applied Mekanistics"},
            {"appflux", "Applied Flux"},
            {"merequester", "ME Requester"},
            {"simibubi", "Create"},
            {"create", "Create"},
            {"mekanism", "Mekanism"},
            {"thermal", "Thermal Series"},
            {"cofh", "Thermal Series (CoFH)"},
            {"sophisticated", "Sophisticated Storage/Backpacks"},
            {"toms_storage", "Tom's Simple Storage"},
            {"storagedrawers", "Storage Drawers"},
            {"refinedstorage", "Refined Storage"},
            {"modularrouters", "Modular Routers"},
            {"justdirethings", "Just Dire Things"},
            {"immersiveengineering", "Immersive Engineering"},
            {"industrialforegoing", "Industrial Foregoing"},
            {"powah", "Powah"},
            {"irons_spellbooks", "Iron's Spells 'n Spellbooks"},
            {"mahoutsukai", "Mahou Tsukai"},
            {"goety", "Goety"},
            {"bloodmagic", "Blood Magic"},
            {"hexerei", "Hexerei"},
            {"biomancy", "Biomancy"},
            {"alexsmobs", "Alex's Mobs"},
            {"alexscaves", "Alex's Caves"},
            {"mowziesmobs", "Mowzie's Mobs"},
            {"cataclysm", "L_Ender's Cataclysm"},
            {"ftbquests", "FTB Quests"},
            {"ftbteams", "FTB Teams"},
            {"ftbchunks", "FTB Chunks"},
            {"ftblibrary", "FTB Library"},
            {"curios", "Curios"},
            {"jei", "JEI"},
            {"supplementaries", "Supplementaries"},
            {"moonlight", "Moonlight Lib"},
            {"waystones", "Waystones"},
            {"corpse", "Corpse"},
            {"lootr", "Lootr"},
            {"polymorph", "Polymorph"},
            {"pehkui", "Pehkui"},
            {"geckolib", "GeckoLib"},
            {"citadel", "Citadel"},
            {"curios", "Curios"},
            {"xaero", "Xaero's Maps"},
            {"playeranim", "Player Animator"},
            {"terrablender", "TerraBlender"},
            {"kubejs", "KubeJS"},
            {"linggango", "Linggango"},
            {"farmersdelight", "Farmer's Delight"},
            {"hostilenetworks", "Hostile Neural Networks"},
            {"l2hostility", "L2 Hostility"},
            {"sanguine", "Sanguine Networks"},
            {"enderstorage", "EnderStorage"},
            {"starcatcher", "Starcatcher"},
            {"easyvillagers", "Easy Villagers"},
            {"titanium", "Titanium"},
            {"embers", "Embers"},
            {"celestisynth", "Celestisynth"},
            {"enigmaticlegacy", "Enigmatic Legacy"},
            {"bossesrise", "Bosses Rise"},
            {"meetyourfight", "Meet Your Fight"},
            {"net.minecraft.network.protocol", "Vanilla"},
            {"net.minecraft", "Vanilla"},
    };

    private PacketAttribution() {
    }

    /**
     * Attributes a custom-payload channel. This is exact, because Forge channel names are
     * always {@code namespace:path}.
     */
    public static String fromChannel(String channel) {
        if (channel == null || channel.isEmpty()) {
            return "Vanilla";
        }
        int colon = channel.indexOf(':');
        String namespace = colon > 0 ? channel.substring(0, colon) : channel;
        String friendly = friendlyName(namespace);
        return friendly != null ? friendly : namespace;
    }

    /** Attributes a packet class by name heuristic. */
    public static String fromClassName(String className) {
        if (className == null) {
            return "Unknown";
        }
        String lower = className.toLowerCase(java.util.Locale.ROOT);
        for (String[] hint : HINTS) {
            if (lower.contains(hint[0])) {
                return hint[1];
            }
        }
        return "Unknown";
    }

    private static String friendlyName(String namespace) {
        String lower = namespace.toLowerCase(java.util.Locale.ROOT);
        for (String[] hint : HINTS) {
            if (lower.equals(hint[0]) || lower.contains(hint[0])) {
                return hint[1];
            }
        }
        return null;
    }
}
