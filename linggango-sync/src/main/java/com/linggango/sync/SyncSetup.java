package com.linggango.sync;

import com.linggango.sync.api.SyncRegistry;
import com.linggango.sync.config.SyncConfig;
import com.linggango.sync.net.SyncRuntime;
import com.linggango.sync.policy.Adapters;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Common-side initialisation, driven from the mod event bus.
 *
 * <p>Kept separate from the {@code @Mod} class so that the main class stays a thin wiring
 * point and this logic can be reasoned about on its own.
 */
@Mod.EventBusSubscriber(modid = LinggangoSync.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SyncSetup {

    private SyncSetup() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        // Config is read before anything else so that a misconfigured or unreadable file
        // still leaves the safe defaults in place rather than failing the load.
        SyncConfig.load(FMLPaths.CONFIGDIR.get());
        SyncRuntime.setConfig(SyncConfig.get());
        SyncRuntime.setMonitoring(SyncConfig.get().debugEnabled());

        // Registering adapters only records metadata. Nothing is optimised until an
        // operator moves the corresponding category off SERVER_AUTHORITATIVE.
        Adapters.registerDefaults();

        LinggangoSync.LOGGER.info("Linggango Sync ready: {} descriptors, monitoring {}",
                SyncRegistry.size(), SyncRuntime.monitoring() ? "enabled" : "disabled");
    }
}
