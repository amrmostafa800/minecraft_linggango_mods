package com.linggango.sync.net.command;

import com.linggango.sync.api.SyncDescriptor;
import com.linggango.sync.api.SyncRegistry;
import com.linggango.sync.cache.CacheEntry;
import com.linggango.sync.cache.ClientCache;
import com.linggango.sync.config.SyncConfig;
import com.linggango.sync.net.ByteFormat;
import com.linggango.sync.net.NetworkMonitor;
import com.linggango.sync.net.NetworkReport;
import com.linggango.sync.net.PacketSample;
import com.linggango.sync.net.SyncRuntime;
import com.linggango.sync.policy.Adapters;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code /linggango-net ...} — operator-only diagnostics.
 *
 * <p>Everything under this command requires permission level 2, so it is invisible to
 * ordinary players. The commands are read-only except {@code reload}, {@code debug} and
 * {@code reset}, and none of them can change gameplay state.
 */
public final class LinggangoNetCommand {

    private LinggangoNetCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("linggango-net")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.literal("stats")
                .executes(ctx -> stats(ctx.getSource(), 15))
                .then(Commands.argument("top", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> stats(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "top")))));

        root.then(Commands.literal("packets")
                .executes(ctx -> packets(ctx.getSource(), 25))
                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 200))
                        .executes(ctx -> packets(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "limit")))));

        root.then(Commands.literal("types")
                .executes(ctx -> types(ctx.getSource(), 25))
                .then(Commands.argument("top", IntegerArgumentType.integer(1, 200))
                        .executes(ctx -> types(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "top")))));

        root.then(Commands.literal("cache").executes(ctx -> cache(ctx.getSource())));

        root.then(Commands.literal("adapters").executes(ctx -> adapters(ctx.getSource())));

        root.then(Commands.literal("policy").executes(ctx -> policy(ctx.getSource())));

        root.then(Commands.literal("debug")
                .then(Commands.literal("on").executes(ctx -> setDebug(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setDebug(ctx.getSource(), false))));

        root.then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())));

        root.then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())));

        root.then(Commands.literal("export")
                .then(Commands.argument("file", StringArgumentType.greedyString())
                        .executes(ctx -> export(ctx.getSource(), StringArgumentType.getString(ctx, "file")))));

        dispatcher.register(root);
    }

    private static int stats(CommandSourceStack source, int top) {
        send(source, "Linggango Sync — network statistics");
        for (String line : NetworkReport.render(SyncRuntime.monitor(), top)) {
            send(source, line);
        }
        return 1;
    }

    private static int packets(CommandSourceStack source, int limit) {
        NetworkMonitor monitor = SyncRuntime.monitor();
        List<PacketSample> recent = monitor.recent(limit);
        send(source, "Recent packets (" + recent.size() + " of " + ByteFormat.count(monitor.totalPackets()) + ")");
        long cutoff = System.currentTimeMillis() - 10_000L;
        for (PacketSample sample : recent) {
            if (sample.timestampMillis() >= cutoff) {
                send(source, "  " + sample.describe());
            }
        }
        send(source, "  (only the last 10 seconds are shown; raise debug.packetHistoryMinutes in config for a longer window)");
        return 1;
    }

    private static int types(CommandSourceStack source, int top) {
        for (String line : NetworkReport.renderByType(SyncRuntime.monitor(), top)) {
            send(source, line);
        }
        return 1;
    }

    private static int cache(CommandSourceStack source) {
        ClientCache cache = SyncRuntime.clientCache();
        if (cache == null) {
            send(source, "No client cache on this side (this is the dedicated server).");
            return 1;
        }
        ClientCache.Stats stats = cache.stats();
        send(source, "Client cache");
        send(source, "  entries: " + stats.entries() + " / " + stats.maxEntries());
        send(source, "  bytes:   " + ByteFormat.bytes(stats.bytes()) + " / " + ByteFormat.bytes(stats.maxBytes()));
        send(source, "  hit rate: " + String.format(java.util.Locale.ROOT, "%.1f%%", stats.hitRate() * 100.0)
                + "  (hits " + ByteFormat.count(stats.hits()) + ", misses " + ByteFormat.count(stats.misses()) + ')');
        send(source, "  evictions: " + ByteFormat.count(stats.evictions())
                + ", expired: " + ByteFormat.count(stats.expired())
                + ", rejected: " + ByteFormat.count(stats.rejected()));
        send(source, "  most recently used:");
        List<CacheEntry> byRecency = cache.byRecency();
        int shown = 0;
        for (int i = byRecency.size() - 1; i >= 0 && shown < 15; i--, shown++) {
            CacheEntry entry = byRecency.get(i);
            send(source, "    " + entry.key() + "  v" + entry.version() + "  " + entry.hash()
                    + "  " + ByteFormat.bytes(entry.rawLength()));
        }
        if (shown == 0) {
            send(source, "    (empty)");
        }
        return 1;
    }

    private static int adapters(CommandSourceStack source) {
        send(source, "Registered sync adapters");
        for (var adapter : Adapters.all()) {
            send(source, "  " + adapter.displayName() + " [" + adapter.id() + "]"
                    + (adapter.isAvailable() ? "" : " (target mod not installed)"));
            for (var rule : adapter.rules()) {
                send(source, "    " + (rule.safe() ? "+ " : "- ") + rule.namespace() + ':' + rule.dataType()
                        + " -> " + rule.defaultPolicy());
                send(source, "        " + rule.rationale());
            }
        }
        return 1;
    }

    private static int policy(CommandSourceStack source) {
        SyncConfig config = SyncConfig.get();
        send(source, "Effective category policies");
        config.categories().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> send(source, "  " + entry.getKey() + " = " + entry.getValue()));

        if (!config.modOverrides().isEmpty()) {
            send(source, "Per-mod overrides");
            config.modOverrides().forEach((namespace, perCategory) ->
                    perCategory.forEach((category, value) ->
                            send(source, "  " + namespace + '.' + category + " = " + value)));
        }

        send(source, "Registered descriptors: " + SyncRegistry.size());
        for (Map.Entry<String, SyncDescriptor> entry : SyncRegistry.snapshot().entrySet()) {
            send(source, "  " + entry.getKey() + " -> " + entry.getValue().policy());
        }
        return 1;
    }

    private static int setDebug(CommandSourceStack source, boolean enabled) {
        SyncRuntime.setMonitoring(enabled);
        SyncConfig.get().setDebugEnabled(enabled);
        send(source, "Packet monitoring " + (enabled ? "enabled" : "disabled") + '.');
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        SyncRuntime.resetMonitor();
        send(source, "Network statistics reset.");
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        Path configDir = FMLPaths.CONFIGDIR.get();
        SyncConfig.load(configDir);
        SyncRuntime.setConfig(SyncConfig.get());
        SyncRuntime.setMonitoring(SyncConfig.get().debugEnabled());
        send(source, "Reloaded " + SyncConfig.path(configDir));
        send(source, "Monitoring is now " + (SyncRuntime.monitoring() ? "enabled" : "disabled") + '.');
        send(source, "Some cache limits take effect on the next client start.");
        return 1;
    }

    private static int export(CommandSourceStack source, String fileArgument) {
        Path out;
        try {
            out = FMLPaths.GAMEDIR.get().resolve("linggangosync").resolve(sanitise(fileArgument));
        } catch (RuntimeException e) {
            send(source, "Invalid file name.");
            return 0;
        }
        try {
            Files.createDirectories(out.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Linggango Sync network report\n");
            for (String line : NetworkReport.render(SyncRuntime.monitor(), 50)) {
                sb.append(line).append('\n');
            }
            sb.append('\n');
            for (String line : NetworkReport.renderByType(SyncRuntime.monitor(), 100)) {
                sb.append(line).append('\n');
            }
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
            send(source, "Wrote report to " + out);
            return 1;
        } catch (IOException e) {
            send(source, "Could not write report: " + e);
            return 0;
        }
    }

    /** Keeps the export inside the game directory: no separators, no traversal. */
    private static String sanitise(String raw) {
        String cleaned = raw.trim().replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replace("..", "").replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "network-report.txt";
        }
        if (!cleaned.contains(".")) {
            cleaned = cleaned + ".txt";
        }
        return cleaned;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
