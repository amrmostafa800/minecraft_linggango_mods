package com.linggango.sync.net;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Emits a rendered network report on a fixed interval while traffic is being observed.
 *
 * <p>Backs the {@code debug.statsIntervalSeconds} setting: a normal session logs where the
 * bytes go without the operator having to run a command. An empty monitor is never
 * reported, and the interval is measured from the last emitted report, so a quiet stretch
 * does not produce a stream of empty summaries.
 *
 * <p>The clock and sink are injected so the cadence can be tested without waiting or
 * writing to a log.
 */
public final class PeriodicReporter {

    private static final int TOP_MODS = 20;

    private final NetworkMonitor monitor;
    private final long intervalMillis;
    private final LongSupplier clock;
    private final Consumer<List<String>> sink;
    private long lastReportMillis;

    public PeriodicReporter(NetworkMonitor monitor, int intervalSeconds, LongSupplier clock, Consumer<List<String>> sink) {
        this.monitor = monitor;
        this.intervalMillis = Math.max(1, intervalSeconds) * 1000L;
        this.clock = clock;
        this.sink = sink;
        this.lastReportMillis = clock.getAsLong();
    }

    /**
     * Emits a report when at least one interval has passed since the last one and the
     * monitor has recorded traffic.
     *
     * @return {@code true} when a report was emitted
     */
    public boolean tick() {
        long now = clock.getAsLong();
        if (now - lastReportMillis < intervalMillis) {
            return false;
        }
        if (monitor.totalPackets() <= 0) {
            return false;
        }
        sink.accept(NetworkReport.render(monitor, TOP_MODS));
        lastReportMillis = now;
        return true;
    }
}
