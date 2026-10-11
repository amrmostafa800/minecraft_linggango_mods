package com.linggango.memfix;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

public final class MemFixConfig {

    public static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> TARGETS;
    private static final ForgeConfigSpec.IntValue INTERVAL_SECONDS;
    private static final ForgeConfigSpec.BooleanValue LOG_PURGES;

    /**
     * Containers proven, by decompiling the shipped jars, to be static maps or
     * sets that are written to and never pruned while holding a player, entity,
     * level or item-stack key that outlives its entry. Format is
     * {@code package.Class#field}. Targets that are missing are skipped.
     *
     * <p>All 36 were confirmed to resolve to genuine static fields in the
     * Linggango 6.6.5b2 jars by {@code VerifyTargets}.
     */
    static final List<String> DEFAULT_TARGETS = List.of(
            // moreartifacts: player-keyed damage and cooldown caches, never cleaned
            "net.gobies.moreartifacts.event.DamageEvents#generalDamageReductionMap",
            "net.gobies.moreartifacts.event.DamageEvents#fireDamageReductionMap",
            "net.gobies.moreartifacts.event.DamageEvents#generalDamageIncreaseMap",
            "net.gobies.moreartifacts.event.DamageEvents#equippedArtifactsMap",
            "net.gobies.moreartifacts.event.DamageEvents#lastHealTimeMap",
            "net.gobies.moreartifacts.util.MAUtils#cooldownMap",
            "net.gobies.moreartifacts.util.Teleport#teleportStatus",
            "net.gobies.moreartifacts.item.artifacts.DragonEyeItem#appliedEffects",
            "net.gobies.moreartifacts.item.artifacts.EnderianTreadsItem#cooldownMap",
            "net.gobies.moreartifacts.item.artifacts.NecroplasmAmuletItem#cooldownMap",
            "net.gobies.moreartifacts.item.artifacts.SpectreAmuletItem#cooldownMap",
            // alexsmobs: world data keyed by Level, one entry per level ever loaded
            "com.github.alexthe666.alexsmobs.world.AMWorldData#dataMap",
            // create-new-age: one entry per level, never removed
            "org.antarcticgardens.cna.content.electricity.network.NetworkTicker#networks",
            // bossesrise: per-ItemStack mining flags
            "net.unusual.blockfactorysbosses.item.DaggerItem#IS_MINING",
            "net.unusual.blockfactorysbosses.item.FrozenFistItem#IS_MINING",
            "net.unusual.blockfactorysbosses.item.KnightSwordItem#IS_MINING",
            "net.unusual.blockfactorysbosses.item.LargeSwordItem#IS_MINING",
            "net.unusual.blockfactorysbosses.item.SandwormDartItem#IS_MINING",
            "net.unusual.blockfactorysbosses.item.WarriorSwordItem#IS_MINING",
            // traveloptics: player cooldowns, never expired
            "com.gametechbc.traveloptics.elemental_interactions.CryostormReaction#wetBlizzardCooldownMap",
            "com.gametechbc.traveloptics.elemental_interactions.HydrochargeReaction#wetChainLightningCooldownMap",
            // ftb-library
            "dev.ftb.mods.ftblibrary.FTBLibraryCommands#EDITING_NBT",
            "dev.ftb.mods.ftblibrary.icon.FaceIcon#CACHE",
            // slashh: combat combo state
            "com.misanthropy.slashh.event.CombatEventHandler#COMBO",
            "com.misanthropy.slashh.event.CombatEventHandler#LAST_HIT_TICK",
            // ad astra, industrial foregoing, natures aura, tons of enchants
            "earth.terrarium.adastra.common.utils.KeybindManager#PLAYER_KEYS",
            "com.buuz135.industrial.block.transportstorage.tile.BlackHoleUnitTile#INTERACTION_LOGGER",
            "de.ellpeck.naturesaura.events.CommonEvents#PENDING_AURA_CHUNKS",
            "com.deltajay.tonsofenchants.enchantments.Enchantments.Excavator#map",
            // dungeon now loading
            "dev.hexnowloading.dungeonnowloading.network.packets.S2CStructureDetectionPacket#playerStructureStatus",
            "dev.hexnowloading.dungeonnowloading.potion.VertexTransmissionEffect#isNoConnectionBeamDamageCaseMap",
            "dev.hexnowloading.dungeonnowloading.supporter.DNLSupporters#supporters",
            // blood magic, animus, ae2
            "wayoftime.bloodmagic.ritual.types.RitualCrushing#cuttingFluidLPMap",
            "wayoftime.bloodmagic.ritual.types.RitualCrushing#cuttingFluidWillMap",
            "com.teamdman.animus.items.sigils.ItemSigilEquivalency#activeOperations",
            "appeng.crafting.pattern.EncodedPatternItem#SIMPLE_CACHE");

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Cross-mod static collection sweeper.").push("memfix");
        ENABLED = b.comment("Master switch.").define("enabled", true);
        INTERVAL_SECONDS = b
                .comment("Seconds between periodic sweeps. 0 disables the periodic sweep;")
                .comment("the level-unload and server-shutdown sweeps still run.")
                .defineInRange("sweepIntervalSeconds", 300, 0, 86400);
        LOG_PURGES = b.comment("Log a line whenever a sweep drops entries.")
                .define("logPurges", true);
        TARGETS = b.comment("Static containers to sweep, as 'package.Class#fieldName'.")
                .defineListAllowEmpty("targets", DEFAULT_TARGETS,
                        o -> o instanceof String s && s.contains("#"));
        SPEC = b.build();
    }

    private static final List<String> ALL = new ArrayList<>();

    private MemFixConfig() {
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static long sweepIntervalMillis() {
        try {
            return INTERVAL_SECONDS.get() * 1000L;
        } catch (Throwable t) {
            return 300_000L;
        }
    }

    public static boolean logPurges() {
        try {
            return LOG_PURGES.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static void bake() {
        List<? extends String> configured;
        if (enabled()) {
            try {
                configured = TARGETS.get();
            } catch (Throwable t) {
                configured = DEFAULT_TARGETS;
            }
        } else {
            configured = List.of();
        }
        ALL.clear();
        ALL.addAll(configured);
    }

    public static void initTargets() {
        Sweeper.setTargets(Sweeper.parse(ALL));
    }

    public static int targetCount() {
        return ALL.size();
    }
}
