package com.linggango.sync.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates observed packet traffic into per-mod, per-direction and per-player totals.
 *
 * <p>Recording is cheap and allocation-light because it happens on the netty event loop
 * for every packet. Aggregation is a single hash lookup plus a few additions; only the
 * bounded sample ring allocates.
 *
 * <p>This class has no Minecraft dependency and is unit tested directly.
 */
public final class NetworkMonitor {

    private final ConcurrentMap<String, Aggregate> byMod = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Aggregate> byType = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Aggregate> byPlayer = new ConcurrentHashMap<>();
    private final ConcurrentMap<PacketDirection, Aggregate> byDirection = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, MinuteBucket> byMinute = new ConcurrentHashMap<>();

    private final Deque<PacketSample> recent = new ArrayDeque<>();
    private final Object recentLock = new Object();

    private final AtomicLong totalPackets = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();

    private final int maxRecentSamples;
    private final int historyMinutes;
    private volatile long windowStartMillis = System.currentTimeMillis();
    private volatile long lastSampleMillis;

    public NetworkMonitor(int maxRecentSamples, int historyMinutes) {
        this.maxRecentSamples = Math.max(1000, maxRecentSamples);
        this.historyMinutes = Math.max(1, historyMinutes);
    }

    public void record(PacketSample sample) {
        totalPackets.incrementAndGet();
        totalBytes.addAndGet(sample.bytes());
        lastSampleMillis = sample.timestampMillis();

        byMod.computeIfAbsent(sample.mod(), k -> new Aggregate()).add(sample);
        byType.computeIfAbsent(sample.direction().label() + ' ' + sample.mod() + " / " + sample.packetType(),
                k -> new Aggregate()).add(sample);
        byDirection.computeIfAbsent(sample.direction(), k -> new Aggregate()).add(sample);
        if (sample.player() != null) {
            byPlayer.computeIfAbsent(sample.player(), k -> new Aggregate()).add(sample);
        }

        long minute = sample.timestampMillis() / 60000L;
        byMinute.computeIfAbsent(minute, k -> new MinuteBucket()).add(sample.bytes());
        pruneMinutes(minute);

        synchronized (recentLock) {
            recent.addLast(sample);
            while (recent.size() > maxRecentSamples) {
                recent.removeFirst();
            }
        }
    }

    private void pruneMinutes(long currentMinute) {
        long cutoff = currentMinute - historyMinutes;
        if (byMinute.size() <= historyMinutes + 2) {
            return;
        }
        byMinute.keySet().removeIf(minute -> minute < cutoff);
    }

    public void reset() {
        byMod.clear();
        byType.clear();
        byPlayer.clear();
        byDirection.clear();
        byMinute.clear();
        synchronized (recentLock) {
            recent.clear();
        }
        totalPackets.set(0);
        totalBytes.set(0);
        windowStartMillis = System.currentTimeMillis();
    }

    public long windowSeconds() {
        long elapsed = (System.currentTimeMillis() - windowStartMillis) / 1000L;
        return Math.max(1L, elapsed);
    }

    public long totalPackets() {
        return totalPackets.get();
    }

    public long totalBytes() {
        return totalBytes.get();
    }

    public List<PacketSample> recent(int limit) {
        List<PacketSample> copy;
        synchronized (recentLock) {
            copy = new ArrayList<>(recent);
        }
        int from = Math.max(0, copy.size() - limit);
        return copy.subList(from, copy.size());
    }

    public Map<String, Aggregate> byMod() {
        return sorted(byMod);
    }

    public Map<String, Aggregate> byType() {
        return sorted(byType);
    }

    public Map<String, Aggregate> byPlayer() {
        return sorted(byPlayer);
    }

    public Map<PacketDirection, Aggregate> byDirection() {
        return new LinkedHashMap<>(byDirection);
    }

    /** Per-minute byte totals, oldest first, for the bandwidth trend line. */
    public List<MinuteBucket> minuteSeries() {
        List<Long> minutes = new ArrayList<>(byMinute.keySet());
        minutes.sort(Comparator.naturalOrder());
        List<MinuteBucket> out = new ArrayList<>(minutes.size());
        for (Long minute : minutes) {
            out.add(byMinute.get(minute));
        }
        return out;
    }

    private static Map<String, Aggregate> sorted(ConcurrentMap<String, Aggregate> source) {
        List<Map.Entry<String, Aggregate>> list = new ArrayList<>(source.entrySet());
        list.sort(Comparator.comparingLong((Map.Entry<String, Aggregate> e) -> e.getValue().bytes()).reversed());
        Map<String, Aggregate> out = new LinkedHashMap<>();
        for (Map.Entry<String, Aggregate> entry : list) {
            out.put(entry.getKey(), entry.getValue());
        }
        return out;
    }

    /** Mutable per-key accumulator. Reads of the counters are racy by design (reporting only). */
    public static final class Aggregate {
        private final AtomicLong packets = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();
        private final AtomicLong maxPacket = new AtomicLong();

        void add(PacketSample sample) {
            packets.incrementAndGet();
            bytes.addAndGet(sample.bytes());
            maxPacket.accumulateAndGet(sample.bytes(), Math::max);
        }

        public long packets() {
            return packets.get();
        }

        public long bytes() {
            return bytes.get();
        }

        public long maxPacketBytes() {
            return maxPacket.get();
        }

        public double averagePacketBytes() {
            long count = packets.get();
            return count == 0 ? 0.0 : (double) bytes.get() / count;
        }
    }

    /** Per-minute accumulator used for the trend line. */
    public static final class MinuteBucket {
        private final AtomicLong packets = new AtomicLong();
        private final AtomicLong bytes = new AtomicLong();

        void add(int size) {
            packets.incrementAndGet();
            bytes.addAndGet(size);
        }

        public long packets() {
            return packets.get();
        }

        public long bytes() {
            return bytes.get();
        }
    }
}
