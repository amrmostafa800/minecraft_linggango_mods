package com.linggango.sync.net;

import com.linggango.sync.LinggangoSync;
import com.linggango.sync.config.SyncConfig;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the periodic network summary on the server tick.
 *
 * <p>This is the consumer of {@code debug.statsIntervalSeconds}. While monitoring is
 * enabled it logs a rendered report every configured number of seconds, so a normal
 * session produces the traffic data without the operator running a command.
 *
 * <p>The reporter is rebuilt only when the configured interval changes, so a
 * {@code /linggango-net reload} takes effect without reallocating on every tick.
 */
@Mod.EventBusSubscriber(modid = LinggangoSync.MOD_ID)
public final class StatsScheduler {

    private static int intervalSeconds = Integer.MIN_VALUE;
    private static PeriodicReporter reporter;

    private StatsScheduler() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !SyncRuntime.monitoring()) {
            return;
        }
        int configured = SyncConfig.get().statsIntervalSeconds();
        if (configured <= 0) {
            return;
        }
        if (reporter == null || configured != intervalSeconds) {
            intervalSeconds = configured;
            reporter = new PeriodicReporter(SyncRuntime.monitor(), configured, System::currentTimeMillis,
                    lines -> {
                        LinggangoSync.LOGGER.info("Linggango Sync periodic network report:");
                        lines.forEach(line -> LinggangoSync.LOGGER.info("  {}", line));
                    });
        }
        reporter.tick();
    }
}
