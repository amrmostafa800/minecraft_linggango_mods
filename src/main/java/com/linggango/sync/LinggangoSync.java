package com.linggango.sync;

import com.linggango.sync.api.SyncRegistry;
import com.linggango.sync.config.SyncConfig;
import com.linggango.sync.net.NetworkReport;
import com.linggango.sync.net.SyncRuntime;
import com.linggango.sync.net.command.LinggangoNetCommand;
import com.linggango.sync.policy.Adapters;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Linggango Sync optimisation mod.
 *
 * <p>The mod never replaces or rewrites another mod's packets. It observes traffic so
 * bottlenecks can be measured, then applies explicit, opt-in adapters to data that is
 * provably safe to cache on the client or synchronise on demand.
 */
@Mod(LinggangoSync.MOD_ID)
public final class LinggangoSync {

    public static final String MOD_ID = "linggangosync";
    public static final Logger LOGGER = LoggerFactory.getLogger("LinggangoSync");

    public LinggangoSync() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LinggangoNetCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (SyncConfig.get().autoReportOnStop() && SyncRuntime.monitoring()) {
            LOGGER.info("Linggango Sync final network report:");
            for (String line : NetworkReport.render(SyncRuntime.monitor(), 20)) {
                LOGGER.info("  {}", line);
            }
        }
    }
}
