package com.linggango.sync.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders a {@link NetworkMonitor} as the operator-facing report. */
public final class NetworkReport {

    private NetworkReport() {
    }

    public static List<String> render(NetworkMonitor monitor, int topMods) {
        List<String> lines = new ArrayList<>();
        long windowSeconds = monitor.windowSeconds();
        long packets = monitor.totalPackets();
        long bytes = monitor.totalBytes();

        lines.add("Network Statistics (window " + ByteFormat.duration(windowSeconds) + ')');
        lines.add("  total: " + ByteFormat.count(packets) + " packets, " + ByteFormat.bytes(bytes)
                + "  (" + ByteFormat.perMinute(bytes, windowSeconds) + ')');
        lines.add("  average packet: " + String.format(java.util.Locale.ROOT, "%.0f B",
                packets == 0 ? 0.0 : (double) bytes / packets));
        long suppressed = SyncRuntime.suppressedPackets();
        if (suppressed > 0) {
            lines.add("  suppressed duplicates: " + ByteFormat.count(suppressed) + " packets, "
                    + ByteFormat.bytes(SyncRuntime.suppressedBytes()));
        }
        lines.add("");

        Map<PacketDirection, NetworkMonitor.Aggregate> directions = monitor.byDirection();
        for (PacketDirection direction : new PacketDirection[]{PacketDirection.SERVER_TO_CLIENT, PacketDirection.CLIENT_TO_SERVER}) {
            NetworkMonitor.Aggregate aggregate = directions.get(direction);
            if (aggregate == null) {
                continue;
            }
            lines.add("  " + direction.label() + ": " + ByteFormat.count(aggregate.packets()) + " packets, "
                    + ByteFormat.bytes(aggregate.bytes()) + "  ("
                    + ByteFormat.perMinute(aggregate.bytes(), windowSeconds) + ')');
        }
        lines.add("");

        lines.add("By mod (top " + topMods + ')');
        int shown = 0;
        for (Map.Entry<String, NetworkMonitor.Aggregate> entry : monitor.byMod().entrySet()) {
            if (shown++ >= topMods) {
                break;
            }
            NetworkMonitor.Aggregate aggregate = entry.getValue();
            lines.add(String.format(java.util.Locale.ROOT, "  %-28s %8s packets  %10s  (%s)",
                    entry.getKey(),
                    ByteFormat.count(aggregate.packets()),
                    ByteFormat.bytes(aggregate.bytes()),
                    ByteFormat.perMinute(aggregate.bytes(), windowSeconds)));
        }
        if (shown == 0) {
            lines.add("  (nothing recorded yet)");
        }
        lines.add("");

        if (!monitor.byPlayer().isEmpty()) {
            lines.add("By player");
            for (Map.Entry<String, NetworkMonitor.Aggregate> entry : monitor.byPlayer().entrySet()) {
                NetworkMonitor.Aggregate aggregate = entry.getValue();
                lines.add(String.format(java.util.Locale.ROOT, "  %-28s %8s packets  %10s  (%s)",
                        entry.getKey(),
                        ByteFormat.count(aggregate.packets()),
                        ByteFormat.bytes(aggregate.bytes()),
                        ByteFormat.perMinute(aggregate.bytes(), windowSeconds)));
            }
            lines.add("");
        }

        List<NetworkMonitor.MinuteBucket> series = monitor.minuteSeries();
        if (series.size() > 1) {
            lines.add("Per-minute bytes (oldest first)");
            long peak = 0;
            for (NetworkMonitor.MinuteBucket bucket : series) {
                peak = Math.max(peak, bucket.bytes());
            }
            for (NetworkMonitor.MinuteBucket bucket : series) {
                lines.add("  " + bar(bucket.bytes(), peak) + ' ' + ByteFormat.bytes(bucket.bytes())
                        + " / " + ByteFormat.count(bucket.packets()) + " packets");
            }
            lines.add("");
        }
        return lines;
    }

    public static List<String> renderByType(NetworkMonitor monitor, int topTypes) {
        List<String> lines = new ArrayList<>();
        long windowSeconds = monitor.windowSeconds();
        lines.add("Packet types (top " + topTypes + ')');
        int shown = 0;
        for (Map.Entry<String, NetworkMonitor.Aggregate> entry : monitor.byType().entrySet()) {
            if (shown++ >= topTypes) {
                break;
            }
            NetworkMonitor.Aggregate aggregate = entry.getValue();
            lines.add(String.format(java.util.Locale.ROOT, "  %-60s %8s  %10s  avg %.0f B  max %s",
                    entry.getKey(),
                    ByteFormat.count(aggregate.packets()),
                    ByteFormat.bytes(aggregate.bytes()),
                    aggregate.averagePacketBytes(),
                    ByteFormat.bytes(aggregate.maxPacketBytes())));
        }
        if (shown == 0) {
            lines.add("  (nothing recorded yet)");
        }
        lines.add("");
        lines.add("  window: " + ByteFormat.duration(windowSeconds)
                + ", server->client " + ByteFormat.perMinute(bytesFor(monitor, PacketDirection.SERVER_TO_CLIENT), windowSeconds)
                + ", client->server " + ByteFormat.perMinute(bytesFor(monitor, PacketDirection.CLIENT_TO_SERVER), windowSeconds));
        return lines;
    }

    private static long bytesFor(NetworkMonitor monitor, PacketDirection direction) {
        NetworkMonitor.Aggregate aggregate = monitor.byDirection().get(direction);
        return aggregate == null ? 0L : aggregate.bytes();
    }

    private static String bar(long value, long peak) {
        int width = peak <= 0 ? 0 : (int) Math.round(24.0 * value / peak);
        return "#".repeat(Math.max(value > 0 ? 1 : 0, width)) + ".".repeat(Math.max(0, 24 - width));
    }
}
