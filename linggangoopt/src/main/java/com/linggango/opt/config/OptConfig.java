package com.linggango.opt.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Configuration for Linggango Optimizer.
 *
 * <p>Defaults are deliberately conservative. The only optimisation that ships enabled by default
 * is the one that cannot change gameplay semantics: replaying a datapack the client already has
 * through the <em>real</em> vanilla handler. Everything else — notably dropping the datapack
 * send entirely — is opt-in, because it trades bytes for a small join-time window where the
 * client's recipe view is not yet refreshed by the server.
 */
public final class OptConfig {

    public static final ForgeConfigSpec SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        SPEC = pair.getRight();
    }

    private OptConfig() {
    }

    public static final class Common {

        public final ForgeConfigSpec.BooleanValue enabled;
        public final ForgeConfigSpec.BooleanValue debug;

        public final ForgeConfigSpec.BooleanValue cacheDatapacks;
        public final ForgeConfigSpec.BooleanValue cacheRecipes;
        public final ForgeConfigSpec.BooleanValue cacheTags;
        public final ForgeConfigSpec.BooleanValue skipServerSendOnHit;
        public final ForgeConfigSpec.BooleanValue verifyHash;

        public final ForgeConfigSpec.BooleanValue wireMonitor;
        public final ForgeConfigSpec.BooleanValue wireLogPerPacket;

        Common(ForgeConfigSpec.Builder b) {
            b.comment("Linggango Optimizer — login-time datapack caching and real-wire instrumentation.")
                    .push("general");
            enabled = b.comment("Master switch. When false every hook is inert and vanilla behaviour is used.")
                    .define("enabled", true);
            debug = b.comment("Verbose logging for cache hits/misses and protocol decisions.")
                    .define("debug", false);

            b.pop();
            b.comment("Datapack payload caching. Recipes and tags are byte-identical for every client "
                            + "and are re-sent on every login (roughly 6.7 MB pre-compression on the Linggango pack).")
                    .push("datapack_cache");
            cacheDatapacks = b.comment("Master switch for datapack caching.")
                    .define("enabled", true);
            cacheRecipes = b.comment("Cache ClientboundUpdateRecipesPacket for reconnect replay.")
                    .define("recipes", true);
            cacheTags = b.comment("Cache ClientboundUpdateTagsPacket for reconnect replay.")
                    .define("tags", true);
            skipServerSendOnHit = b.comment("When the client already holds the current datapack hash, do not send the "
                            + "payload at all. This is the only byte-saving option and is OFF by default: the client "
                            + "replays the cached structs through the real handler (so on-disk data stays correct), but "
                            + "the client's recipe view is not refreshed by the server until the next datapack sync. "
                            + "Safe for a private server; leave false if any datapack-driven mod is served.")
                    .define("skip_server_send_on_hash_hit", false);
            verifyHash = b.comment("Compare SHA-256 of the fresh payload to the client's cached hash. When false only "
                            + "the cheap payload-set version is compared (still correct across pack edits, which always "
                            + "bump recipe count).")
                    .define("verify_hash", true);

            b.pop();
            b.comment("Real-wire instrumentation. Measures bytes after zlib compression, which the first mod's "
                            + "packet-encoder hook cannot see.")
                    .push("wire_monitor");
            wireMonitor = b.comment("Enable post-compression byte accounting.")
                    .define("enabled", false);
            wireLogPerPacket = b.comment("Log every compressed frame (very noisy).")
                    .define("log_per_packet", false);
        }
    }
}
