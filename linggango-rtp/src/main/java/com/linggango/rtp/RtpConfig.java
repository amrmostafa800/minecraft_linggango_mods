package com.linggango.rtp;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Operator-facing configuration for the {@code /rtp} search, written to
 * {@code config/linggango-rtp-common.toml}.
 *
 * <p>Every getter returns a safe default on any {@link Throwable}: Forge has not
 * loaded the config while mods are being constructed, so a getter may be called
 * before the values exist. The defaults mirror the shipped TOML exactly.
 */
public final class RtpConfig {

    public static final ForgeConfigSpec SPEC;

    /**
     * EssentialsX's ocean and river set, plus the biomes the design adds for the
     * Linggango pack: the Deep Dark, Mushroom Fields, the Void and the outer End
     * islands/highlands/midlands/barrens, none of which are places to drop a
     * player into.
     */
    static final List<String> DEFAULT_EXCLUDED_BIOMES = List.of(
            "minecraft:ocean",
            "minecraft:deep_ocean",
            "minecraft:cold_ocean",
            "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean",
            "minecraft:deep_frozen_ocean",
            "minecraft:lukewarm_ocean",
            "minecraft:deep_lukewarm_ocean",
            "minecraft:warm_ocean",
            "minecraft:deep_warm_ocean",
            "minecraft:river",
            "minecraft:frozen_river",
            "minecraft:deep_dark",
            "minecraft:mushroom_fields",
            "minecraft:the_void",
            "minecraft:small_end_islands",
            "minecraft:end_highlands",
            "minecraft:end_midlands",
            "minecraft:end_barrens");

    private static final Set<String> DEFAULT_EXCLUDED_BIOME_SET = toLowerSet(DEFAULT_EXCLUDED_BIOMES);

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.IntValue CENTER_X;
    private static final ForgeConfigSpec.IntValue CENTER_Z;
    private static final ForgeConfigSpec.IntValue MIN_RADIUS;
    private static final ForgeConfigSpec.IntValue MAX_RADIUS;
    private static final ForgeConfigSpec.IntValue FIND_ATTEMPTS;
    private static final ForgeConfigSpec.BooleanValue EXISTING_CHUNKS_ONLY;
    private static final ForgeConfigSpec.IntValue CHUNK_MARGIN;
    private static final ForgeConfigSpec.BooleanValue RESPECT_CLAIMS;
    private static final ForgeConfigSpec.BooleanValue DEBUG;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_BIOMES;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Asynchronous /rtp location search.").push("rtp");
        ENABLED = b.comment("Master switch. When false the mod does not intercept /rtp")
                .define("enabled", true);
        CENTER_X = b.comment("Centre X of the search area.")
                .defineInRange("centerX", 0, -30000000, 30000000);
        CENTER_Z = b.comment("Centre Z of the search area.")
                .defineInRange("centerZ", 0, -30000000, 30000000);
        MIN_RADIUS = b.comment("Minimum distance from the centre, in blocks.")
                .defineInRange("minRadius", 500, 0, 30000000);
        MAX_RADIUS = b.comment("Maximum distance from the centre, in blocks.",
                        "Keep this within the world's already-generated area: with",
                        "existingChunksOnly = true, candidates outside it are rejected",
                        "and the search simply runs out of attempts.")
                .defineInRange("maxRadius", 2048, 0, 30000000);
        FIND_ATTEMPTS = b.comment("Candidates to try before giving up. Each attempt is cheap",
                        "when existingChunksOnly rejects ungenerated terrain, so this can be high.")
                .defineInRange("findAttempts", 40, 1, 1000);
        EXISTING_CHUNKS_ONLY = b.comment("Only consider candidates whose chunk (and its",
                        "neighbours, see chunkMargin) are already written to disk. This is what",
                        "keeps a search from generating terrain and stalling the server. Turn it",
                        "off only to reach never-generated terrain, accepting the tick stalls.")
                .define("existingChunksOnly", true);
        CHUNK_MARGIN = b.comment("Neighbour margin required on disk around a candidate, in chunks.",
                        "1 means the full 3x3 neighbourhood must exist, which is what stops",
                        "loading a candidate from generating its neighbours.")
                .defineInRange("chunkMargin", 1, 0, 2);
        RESPECT_CLAIMS = b.comment("Skip candidates inside an FTB Chunks claim.")
                .define("respectClaims", true);
        DEBUG = b.comment("Log each /rtp search decision (candidates rejected on disk, landing",
                        "rejections, retries) at INFO. Off by default; turn it on to see exactly",
                        "why a search fails or takes long.")
                .define("debug", false);
        EXCLUDED_BIOMES = b.comment("Biomes /rtp will not land in, as 'namespace:path'.")
                .defineListAllowEmpty("excludedBiomes", DEFAULT_EXCLUDED_BIOMES,
                        o -> o instanceof String);
        b.pop();
        SPEC = b.build();
    }

    private RtpConfig() {
    }

    public static boolean enabled() {
        try {
            return ENABLED.get();
        } catch (Throwable t) {
            return true;
        }
    }

    public static int centerX() {
        try {
            return CENTER_X.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int centerZ() {
        try {
            return CENTER_Z.get();
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int minRadius() {
        try {
            return MIN_RADIUS.get();
        } catch (Throwable t) {
            return 500;
        }
    }

    public static int maxRadius() {
        try {
            return MAX_RADIUS.get();
        } catch (Throwable t) {
            return 2048;
        }
    }

    public static int findAttempts() {
        try {
            return FIND_ATTEMPTS.get();
        } catch (Throwable t) {
            return 40;
        }
    }

    /** True when candidates must already be written to disk. Default true. */
    public static boolean existingChunksOnly() {
        try {
            return EXISTING_CHUNKS_ONLY.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Neighbour margin, in chunks, required on disk around a candidate. */
    public static int chunkMargin() {
        try {
            return CHUNK_MARGIN.get();
        } catch (Throwable t) {
            return 1;
        }
    }

    public static boolean respectClaims() {
        try {
            return RESPECT_CLAIMS.get();
        } catch (Throwable t) {
            return true;
        }
    }

    /** True when the search should log each decision it makes. Default false. */
    public static boolean debug() {
        try {
            return DEBUG.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static Set<String> excludedBiomes() {
        try {
            return toLowerSet(EXCLUDED_BIOMES.get());
        } catch (Throwable t) {
            return DEFAULT_EXCLUDED_BIOME_SET;
        }
    }

    private static Set<String> toLowerSet(List<? extends String> values) {
        Set<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null) {
                set.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(set);
    }
}
