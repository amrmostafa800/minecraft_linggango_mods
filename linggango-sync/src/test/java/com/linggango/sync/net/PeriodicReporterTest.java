package com.linggango.sync.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The periodic reporter must emit at most one report per configured interval, and must
 * never emit an empty one.
 */
class PeriodicReporterTest {

    private static PacketSample sample(int bytes) {
        return new PacketSample(System.currentTimeMillis(), PacketDirection.SERVER_TO_CLIENT, PacketKind.PLAY,
                "Vanilla", "SomePacket", null, bytes, null);
    }

    @Test
    void emitsOnceWhenTheIntervalElapsesWithTraffic() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample(100));
        List<List<String>> reports = new ArrayList<>();
        long[] now = {0L};
        PeriodicReporter reporter = new PeriodicReporter(monitor, 60, () -> now[0], reports::add);

        assertFalse(reporter.tick(), "no report before the interval elapses");
        now[0] = 59_999L;
        assertFalse(reporter.tick(), "still inside the interval");

        now[0] = 60_000L;
        assertTrue(reporter.tick(), "interval elapsed with traffic -> report");
        assertEquals(1, reports.size());
        assertFalse(reports.get(0).isEmpty(), "report carries rendered lines");
    }

    @Test
    void staysSilentWhenThereIsNoTraffic() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        List<List<String>> reports = new ArrayList<>();
        long[] now = {0L};
        PeriodicReporter reporter = new PeriodicReporter(monitor, 60, () -> now[0], reports::add);

        now[0] = 600_000L;
        assertFalse(reporter.tick(), "an empty monitor must not produce a report");
        assertEquals(0, reports.size());
    }

    @Test
    void waitsAFullIntervalBetweenReports() {
        NetworkMonitor monitor = new NetworkMonitor(1000, 5);
        monitor.record(sample(100));
        List<List<String>> reports = new ArrayList<>();
        long[] now = {0L};
        PeriodicReporter reporter = new PeriodicReporter(monitor, 60, () -> now[0], reports::add);

        now[0] = 60_000L;
        assertTrue(reporter.tick());
        assertFalse(reporter.tick(), "same instant -> no second report");
        now[0] = 120_000L;
        assertTrue(reporter.tick());
        assertEquals(2, reports.size());
    }
}
