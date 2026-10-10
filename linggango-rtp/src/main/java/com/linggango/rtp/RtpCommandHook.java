package com.linggango.rtp;

import java.util.List;

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
 * only while no cooldown is active, and only while FTB Essentials is present and
 * the mod is enabled. Every other case falls through to FTB untouched, so its
 * cooldown message, warm-up, End blacklist and history behaviour are preserved.
 *
 * <p>The event bus dispatch and the reflective FTB/config lookups can throw for
 * reasons outside this mod's control; the handler treats any {@link Throwable}
 * as "do nothing" rather than as an interception, so a failure can never
 * accidentally cancel a command the mod does not own.
 */
public final class RtpCommandHook {

    private static final String RTP = "rtp";
    private static final String OVERWORLD = "minecraft:overworld";

    public RtpCommandHook() {
    }

    /**
     * The pure decision behind {@link #onCommand}: true only for an overworld
     * {@code /rtp} with no active cooldown, FTB Essentials present and the mod
     * enabled.
     */
    static boolean shouldIntercept(String rootCommand, String dimensionId, boolean onCooldown,
                                   boolean ftbAvailable, boolean enabled) {
        return RTP.equals(rootCommand)
                && OVERWORLD.equals(dimensionId)
                && !onCooldown
                && ftbAvailable
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
            boolean enabled = RtpConfig.enabled();

            if (shouldIntercept(root, dimensionId, onCooldown, ftbAvailable, enabled)) {
                event.setCanceled(true);
                RtpSearch.start(player);
            }
        } catch (Throwable t) {
            // Any failure must leave the command alone: FTB still owns /rtp and
            // will handle (or refuse) it. Never cancel here.
        }
    }
}
