package com.linggango.rtp;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Intercepts {@code /rtp} on the Forge event bus and replaces FTB Essentials'
 * synchronous implementation with {@link RtpSearch} — but only in the overworld,
 * only while no cooldown is active, only while FTB Essentials is present and its
 * own {@code RTP.enabled} gate is on, and only while this mod is enabled. Every
 * other case falls through to FTB untouched, so its cooldown message, warm-up,
 * End blacklist and history behaviour are preserved.
 *
 * <p>The event bus dispatch and the reflective FTB/config lookups can throw for
 * reasons outside this mod's control; the handler treats any {@link Throwable}
 * as "do nothing" rather than as an interception, so a failure can never
 * accidentally cancel a command the mod does not own. The failure is logged once
 * so it is not silent.
 */
public final class RtpCommandHook {

    private static final String RTP = "rtp";
    private static final String OVERWORLD = "minecraft:overworld";

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    public RtpCommandHook() {
    }

    /**
     * The pure decision behind {@link #onCommand}: true only for an overworld
     * {@code /rtp} with no active cooldown, FTB Essentials present, FTB's own
     * {@code RTP.enabled} gate on, and this mod enabled.
     */
    static boolean shouldIntercept(String rootCommand, String dimensionId, boolean onCooldown,
                                   boolean ftbAvailable, boolean rtpEnabled, boolean enabled) {
        return RTP.equals(rootCommand)
                && OVERWORLD.equals(dimensionId)
                && !onCooldown
                && ftbAvailable
                && rtpEnabled
                && enabled;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onCommand(CommandEvent event) {
        try {
            ParseResults<CommandSourceStack> results = event.getParseResults();
            List<ParsedCommandNode<CommandSourceStack>> nodes = results.getContext().getNodes();
            if (nodes == null || nodes.isEmpty()) {
                return;
            }
            String root = nodes.get(0).getNode().getName();
            if (!RTP.equals(root)) {
                return;
            }

            CommandSourceStack source = results.getContext().getSource();
            ServerPlayer player = source.getPlayer();
            if (player == null) {
                return;
            }

            String dimensionId = player.serverLevel().dimension().location().toString();
            boolean onCooldown = FtbAccess.checkCooldown(player);
            boolean ftbAvailable = FtbAccess.available();
            boolean rtpEnabled = FtbAccess.rtpEnabled();
            boolean enabled = RtpConfig.enabled();

            if (shouldIntercept(root, dimensionId, onCooldown, ftbAvailable, rtpEnabled, enabled)) {
                event.setCanceled(true);
                RtpSearch.start(player);
            }
        } catch (Throwable t) {
            // Any failure must leave the command alone: FTB still owns /rtp and
            // will handle (or refuse) it. Never cancel here, but do not swallow
            // the failure silently.
            warnOnce(t);
        }
    }

    private static void warnOnce(Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LinggangoRtp.LOGGER.warn("[rtp] command hook failed; leaving /rtp to FTB ({})", t.toString());
        }
    }
}
