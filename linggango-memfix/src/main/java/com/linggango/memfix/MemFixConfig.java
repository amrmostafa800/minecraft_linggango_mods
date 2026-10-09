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
    private static final ForgeConfigSpec.BooleanValue RTP_GUARD;
    private static final ForgeConfigSpec.BooleanValue PREGEN_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> PREGEN_DIMENSION;
    private static final ForgeConfigSpec.IntValue PREGEN_RADIUS;
    private static final ForgeConfigSpec.IntValue PREGEN_CENTER_X;
    private static final ForgeConfigSpec.IntValue PREGEN_CENTER_Z;
    private static final ForgeConfigSpec.IntValue PREGEN_PER_TICK;
    private static final ForgeConfigSpec.IntValue PREGEN_IN_FLIGHT;

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

        b.comment("Random teleport.")
                .comment("FTB Essentials' /rtp generates terrain on the server thread, which")
                .comment("stalls the whole server. The guard declines candidates whose chunk")
                .comment("has not been generated yet, so the command moves on instead of")
                .comment("freezing. Keep the pregenerator below at least as large as")
                .comment("rtp.max_distance, otherwise /rtp will report that it could not")
                .comment("find a location.")
                .push("rtp");
        RTP_GUARD = b.comment("Stop /rtp from generating terrain on the server thread.")
                .define("guard", true);
        PREGEN_ENABLED = b.comment("Generate the /rtp area in the background, once, at startup.")
                .define("pregenerate", true);
        PREGEN_DIMENSION = b.comment("Dimension to pregenerate.")
                .define("dimension", "minecraft:overworld");
        PREGEN_RADIUS = b.comment("Radius in blocks. Match or exceed FTB's rtp.max_distance.")
                .comment("Generation time grows with the square of this value; 2048 takes")
                .comment("about 45 minutes on four cores, 4096 about three hours.")
                .defineInRange("radiusBlocks", 2048, 0, 25000);
        PREGEN_CENTER_X = b.comment("Centre X. /rtp measures its distance from the origin.")
                .defineInRange("centerX", 0, -30000000, 30000000);
        PREGEN_CENTER_Z = b.comment("Centre Z. /rtp measures its distance from the origin.")
                .defineInRange("centerZ", 0, -30000000, 30000000);
        PREGEN_PER_TICK = b.comment("Chunks submitted per tick. Higher finishes sooner and")
                .comment("loads the CPU more while it runs.")
                .defineInRange("chunksPerTick", 4, 1, 64);
        PREGEN_IN_FLIGHT = b.comment("Chunks allowed to be generating at once.")
                .defineInRange("maxInFlight", 64, 1, 512);
        b.pop();
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

    public static boolean rtpGuardEnabled() {
        try {
            return RTP_GUARD.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean pregenEnabled() {
        try {
            return PREGEN_ENABLED.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static String pregenDimension() {
        try {
            return PREGEN_DIMENSION.get();
        } catch (Throwable t) {
            return "minecraft:overworld";
        }
    }

    public static int pregenRadiusBlocks() {
        try {
            return PREGEN_RADIUS.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int pregenCenterX() {
        try {
            return PREGEN_CENTER_X.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int pregenCenterZ() {
        try {
            return PREGEN_CENTER_Z.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int pregenChunksPerTick() {
        try {
            return PREGEN_PER_TICK.get();
        } catch (Throwable t) {
            return 4;
        }
    }

    public static int pregenMaxInFlight() {
        try {
            return PREGEN_IN_FLIGHT.get();
        } catch (Throwable t) {
            return 64;
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
