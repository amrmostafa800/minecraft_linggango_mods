package com.linggango.sync.net;

import com.linggango.sync.LinggangoSync;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps the {@link InterestManager} fed with live player positions on the server.
 *
 * <p>Positions are sampled once per second rather than every tick. Interest decisions are
 * about whether a player is near a machine, and a one-second granularity is far finer than
 * any meaningful distance change at player walking speed, while costing a fraction of the
 * work. The sample is also skipped entirely when no adapter is using interest management.
 */
@Mod.EventBusSubscriber(modid = LinggangoSync.MOD_ID)
public final class ServerInterestTracker {

    private static final int SAMPLE_INTERVAL_TICKS = 20;

    private static final InterestManager INTEREST = new InterestManager(64.0, 256.0);
    private static int tickCounter;

    private ServerInterestTracker() {
    }

    public static InterestManager interest() {
        return INTEREST;
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            record(player);
            LinggangoSync.LOGGER.debug("Tracking {} for interest management", player.getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String name = player.getGameProfile().getName();
            INTEREST.forget(name);
            PlayerNames.forgetPlayer(name);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            record(player);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        if (event.getServer() == null) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            record(player);
        }
    }

    private static void record(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        INTEREST.track(new ViewerPosition(name, player.getX(), player.getY(), player.getZ()));
        INTEREST.trackDimension(name, player.level().dimension().location().toString());
    }
}
