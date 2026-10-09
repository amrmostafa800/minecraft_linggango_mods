package com.linggango.sync.net;

import com.linggango.sync.cache.ClientCache;
import com.linggango.sync.config.SyncConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide runtime state: the traffic monitor, the (client-only) cache and the
 * master enable flag.
 *
 * <p>Deliberately free of Minecraft types so the mixin and the unit tests can reach it
 * without a game launch.
 */
public final class SyncRuntime {

    private static final AtomicBoolean MONITORING = new AtomicBoolean(false);
    private static volatile NetworkMonitor monitor;
    private static volatile ClientCache clientCache;
    private static volatile SyncConfig config = SyncConfig.get();

    private SyncRuntime() {
    }

    public static boolean monitoring() {
        return MONITORING.get();
    }

    public static void setMonitoring(boolean value) {
        MONITORING.set(value);
    }

    public static NetworkMonitor monitor() {
        NetworkMonitor current = monitor;
        if (current == null) {
            synchronized (SyncRuntime.class) {
                if (monitor == null) {
                    SyncConfig cfg = config;
                    monitor = new NetworkMonitor(20000, cfg.packetHistoryMinutes());
                }
                current = monitor;
            }
        }
        return current;
    }

    public static void resetMonitor() {
        synchronized (SyncRuntime.class) {
            SyncConfig cfg = config;
            monitor = new NetworkMonitor(20000, cfg.packetHistoryMinutes());
        }
    }

    public static ClientCache clientCache() {
        return clientCache;
    }

    public static void setClientCache(ClientCache cache) {
        clientCache = cache;
    }

    public static SyncConfig config() {
        return config;
    }

    public static void setConfig(SyncConfig value) {
        if (value != null) {
            config = value;
        }
    }
}
