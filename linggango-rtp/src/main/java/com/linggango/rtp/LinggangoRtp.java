package com.linggango.rtp;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LinggangoRtp.MODID)
public class LinggangoRtp {
    public static final String MODID = "linggango_rtp";
    public static final Logger LOGGER = LoggerFactory.getLogger("linggango-rtp");

    public LinggangoRtp() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RtpConfig.SPEC,
                "linggango-rtp-common.toml");
        MinecraftForge.EVENT_BUS.register(new Events());
        LOGGER.info("[rtp] loaded");
    }

    /**
     * Cancels any in-flight search when its player can no longer be teleported
     * to: on logout, dimension change, death, and when the server stops. Without
     * these, a callback could fire after the player is gone.
     */
    private static final class Events {

        @SubscribeEvent
        public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            RtpSearch.cancel(event.getEntity().getUUID());
        }

        @SubscribeEvent
        public void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            RtpSearch.cancel(event.getEntity().getUUID());
        }

        @SubscribeEvent
        public void onLivingDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                RtpSearch.cancel(player.getUUID());
            }
        }

        @SubscribeEvent
        public void onServerStopped(ServerStoppedEvent event) {
            RtpSearch.cancelAll();
        }
    }
}
