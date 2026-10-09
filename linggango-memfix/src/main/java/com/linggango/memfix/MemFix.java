package com.linggango.memfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prunes leaked static-collection entries out of other mods.
 *
 * <p>The pack ships 400+ mods, many of which keep a {@code static Map} keyed by
 * a player, entity, level or item stack and never remove entries when those go
 * away. On a long-running server that grows without bound. This mod sweeps a
 * configured list of such containers on startup, on level unload, on a periodic
 * tick, and on server stop.
 */
@Mod(MemFix.MODID)
public class MemFix {

    public static final String MODID = "memfix";
    public static final Logger LOGGER = LoggerFactory.getLogger("memfix");

    public MemFix() {
        // Game lifecycle events go on the Forge bus; mod lifecycle events such
        // as ModConfigEvent go on the mod bus.
        MinecraftForge.EVENT_BUS.register(new Events());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MemFixConfig.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().register(new ModEvents());
    }

    /**
     * Config values are still defaults while mods are being constructed, so the
     * target list is rebuilt once the config actually loads.
     */
    static void onConfigLoaded() {
        MemFixConfig.bake();
        MemFixConfig.initTargets();
        LOGGER.info("[memfix] loaded {} leak targets", MemFixConfig.targetCount());
        if (RtpGuard.install()) {
            LOGGER.info("[memfix] /rtp guard installed (rtp.guard={}, pregenerate={})",
                    MemFixConfig.rtpGuardEnabled(), MemFixConfig.pregenEnabled());
        }
    }

    private static final class Events {

        @SubscribeEvent
        public void onServerStarted(ServerStartedEvent event) {
            int n = Sweeper.sweep(event.getServer());
            LOGGER.info("[memfix] startup sweep dropped {} entries", n);
            ChunkPregen.start(event.getServer());
        }

        @SubscribeEvent
        public void onServerStopped(ServerStoppedEvent event) {
            ChunkPregen.stop();
            int n = Sweeper.sweep(null);
            LOGGER.info("[memfix] server stop sweep dropped {} entries (total {} since start)",
                    n, Sweeper.getTotalPurged());
        }

        @SubscribeEvent
        public void onLevelUnload(LevelEvent.Unload event) {
            Sweeper.sweep(null);
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            ChunkPregen.tick(event.getServer());
            if (!Sweeper.dueForSweep(MemFixConfig.sweepIntervalMillis())) {
                return;
            }
            int n = Sweeper.sweep(event.getServer());
            if (n > 0 && MemFixConfig.logPurges()) {
                LOGGER.info("[memfix] periodic sweep dropped {} entries", n);
            }
        }
    }

    /** Mod-bus events: the config is only readable once these fire. */
    private static final class ModEvents {

        @SubscribeEvent
        public void onConfigLoad(ModConfigEvent.Loading event) {
            if (MemFixConfig.SPEC == event.getConfig().getSpec()) {
                onConfigLoaded();
            }
        }

        @SubscribeEvent
        public void onConfigReload(ModConfigEvent.Reloading event) {
            if (MemFixConfig.SPEC == event.getConfig().getSpec()) {
                onConfigLoaded();
            }
        }
    }
}
