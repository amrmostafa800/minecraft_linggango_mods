package com.linggango.sync.net;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers packet accounting, per-mod attribution and the rendered report. */
class NetworkMonitorTest {

    private static PacketSample sample(String mod, PacketDirection direction, int bytes, String player) {
        return new PacketSample(System.currentTimeMillis(), direction, PacketKind.PLAY,
                mod, "SomePacket", null, bytes, player);
    }

    @Test
    void accumulatesTotalsPerModAndDirection() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 500, "alice"));
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 300, "alice"));
        monitor.record(sample("AE2", PacketDirection.SERVER_TO_CLIENT, 100, "bob"));

        assertEquals(3, monitor.totalPackets());
        assertEquals(900, monitor.totalBytes());

        Map<String, NetworkMonitor.Aggregate> byMod = monitor.byMod();
        assertEquals(800, byMod.get("Create").bytes());
        assertEquals(2, byMod.get("Create").packets());
        assertEquals(400.0, byMod.get("Create").averagePacketBytes());
        assertEquals(100, byMod.get("AE2").bytes());
    }

    @Test
    void sortsModsByBytesDescending() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Small", PacketDirection.SERVER_TO_CLIENT, 10, null));
        monitor.record(sample("Big", PacketDirection.SERVER_TO_CLIENT, 10_000, null));
        monitor.record(sample("Medium", PacketDirection.SERVER_TO_CLIENT, 1_000, null));

        assertEquals(List.of("Big", "Medium", "Small"), List.copyOf(monitor.byMod().keySet()));
    }

    @Test
    void tracksBothDirectionsSeparately() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 700, null));
        monitor.record(sample("Create", PacketDirection.CLIENT_TO_SERVER, 200, null));

        assertEquals(700, monitor.byDirection().get(PacketDirection.SERVER_TO_CLIENT).bytes());
        assertEquals(200, monitor.byDirection().get(PacketDirection.CLIENT_TO_SERVER).bytes());
    }

    @Test
    void tracksPerPlayer() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 100, "alice"));
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 250, "bob"));
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 50, null));

        assertEquals(100, monitor.byPlayer().get("alice").bytes());
        assertEquals(250, monitor.byPlayer().get("bob").bytes());
        assertFalse(monitor.byPlayer().containsKey("null"));
    }

    @Test
    void tracksPeakAndAveragePacketSize() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("M", PacketDirection.SERVER_TO_CLIENT, 100, null));
        monitor.record(sample("M", PacketDirection.SERVER_TO_CLIENT, 900, null));

        NetworkMonitor.Aggregate aggregate = monitor.byMod().get("M");
        assertEquals(900, aggregate.maxPacketBytes());
        assertEquals(500.0, aggregate.averagePacketBytes());
    }

    @Test
    void recentSamplesAreBoundedAndNewestLast() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        for (int i = 0; i < 5000; i++) {
            monitor.record(sample("M", PacketDirection.SERVER_TO_CLIENT, i, null));
        }
        List<PacketSample> recent = monitor.recent(10);
        assertEquals(10, recent.size());
        assertEquals(4999, recent.get(9).bytes(), "newest sample should be last");
        assertEquals(4990, recent.get(0).bytes());
    }

    @Test
    void resetClearsEverything() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("M", PacketDirection.SERVER_TO_CLIENT, 100, "alice"));
        monitor.reset();

        assertEquals(0, monitor.totalPackets());
        assertEquals(0, monitor.totalBytes());
        assertTrue(monitor.byMod().isEmpty());
        assertTrue(monitor.recent(10).isEmpty());
    }

    @Test
    void reportRendersModBreakdown() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 12_400_000, "alice"));
        monitor.record(sample("AE2", PacketDirection.SERVER_TO_CLIENT, 8_100_000, "alice"));
        monitor.record(sample("Vanilla", PacketDirection.CLIENT_TO_SERVER, 900_000, "alice"));

        List<String> lines = NetworkReport.render(monitor, 10);
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("Network Statistics"), joined);
        assertTrue(joined.contains("Create"), joined);
        assertTrue(joined.contains("AE2"), joined);
        assertTrue(joined.contains("S->C"), joined);
        assertTrue(joined.contains("C->S"), joined);
        assertTrue(joined.contains("By player"), joined);
    }

    @Test
    void reportHandlesEmptyMonitor() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        List<String> lines = NetworkReport.render(monitor, 10);
        assertTrue(String.join("\n", lines).contains("nothing recorded yet"));
    }

    @Test
    void typeReportShowsAverages() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 400, null));
        monitor.record(sample("Create", PacketDirection.SERVER_TO_CLIENT, 600, null));

        String joined = String.join("\n", NetworkReport.renderByType(monitor, 10));
        assertTrue(joined.contains("avg 500 B"), joined);
    }

    @Test
    void byteFormattingIsReadable() {
        assertEquals("512 B", ByteFormat.bytes(512));
        assertEquals("1.0 KB", ByteFormat.bytes(1024));
        assertEquals("1.5 KB", ByteFormat.bytes(1536));
        assertEquals("1.0 MB", ByteFormat.bytes(1024L * 1024L));
        assertEquals("1,234", ByteFormat.count(1234));
    }

    @Test
    void minuteSeriesIsOrderedOldestFirst() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample("M", PacketDirection.SERVER_TO_CLIENT, 10, null));
        List<NetworkMonitor.MinuteBucket> series = monitor.minuteSeries();
        assertEquals(1, series.size());
        assertEquals(10, series.get(0).bytes());
    }
}
