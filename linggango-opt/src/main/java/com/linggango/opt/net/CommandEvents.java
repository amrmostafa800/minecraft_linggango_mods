package com.linggango.opt.net;

import com.linggango.opt.bench.WireStats;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * Diagnostics. Kept tiny and read-only.
 *
 * <p>{@code /linggango-opt wire} reports post-compression totals. Server-side use is gated to
 * operators; on a client the command also works against the local meter. Nothing here mutates
 * world or network state.
 */
public final class CommandEvents {

    private CommandEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(CommandEvents.class);
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("linggango-opt")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("wire").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(WireStats.report()), false);
                    return 1;
                }))
                .then(Commands.literal("cache").executes(ctx -> {
                    String msg = describeCache();
                    ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }));
        event.getDispatcher().register(root);
    }

    private static String describeCache() {
        if (OptRuntime.isClient() && OptRuntime.cache() != null) {
            var snap = OptRuntime.cache().snapshot();
            StringBuilder sb = new StringBuilder("[opt] client datapack cache: ");
            if (snap.isEmpty()) {
                sb.append("empty");
            } else {
                snap.forEach((k, v) -> sb.append(k.id()).append('=').append(v.data().length).append("B "));
            }
            return sb.toString();
        }
        if (OptRuntime.isServer() && OptRuntime.claims() != null) {
            return "[opt] server skips=" + OptRuntime.claims().totalSkips();
        }
        return "[opt] no cache on this side";
    }
}
