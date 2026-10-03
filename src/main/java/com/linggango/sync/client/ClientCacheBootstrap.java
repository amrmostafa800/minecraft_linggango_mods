package com.linggango.sync.client;

import com.linggango.sync.LinggangoSync;
import com.linggango.sync.cache.CacheStore;
import com.linggango.sync.cache.ClientCache;
import com.linggango.sync.cache.DiskCacheStore;
import com.linggango.sync.config.SyncConfig;
import com.linggango.sync.net.SyncRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.nio.file.Path;

/**
 * Client-only bootstrap. Loads the on-disk cache at startup and flushes it at shutdown.
 *
 * <p>The cache lives under {@code <gamedir>/linggangosync/cache.bin}. It is keyed by the
 * server's identity so that joining a different world does not serve stale data from
 * another server.
 */
@Mod.EventBusSubscriber(modid = LinggangoSync.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientCacheBootstrap {

    private ClientCacheBootstrap() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        initialise(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get());
    }

    public static void initialise(Path gameDir) {
        SyncConfig config = SyncConfig.get();
        Path cacheFile = gameDir.resolve(LinggangoSync.MOD_ID).resolve("cache.bin");

        CacheStore store = config.cachePersistToDisk()
                ? new DiskCacheStore(cacheFile, config.cacheCompressOnDisk())
                : CacheStore.NOOP;

        ClientCache cache = new ClientCache(store, config.cacheMaxEntries(), config.cacheMaxBytes(), config.cacheTtlSeconds());
        SyncRuntime.setClientCache(cache);

        LinggangoSync.LOGGER.info("Client cache ready at {} ({} entries, {})",
                cacheFile, cache.size(), com.linggango.sync.net.ByteFormat.bytes(cache.bytes()));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cache.flush();
            LinggangoSync.LOGGER.info("Client cache flushed ({} entries, {} bytes)",
                    cache.size(), cache.bytes());
        }, "LinggangoSync-CacheFlush"));
    }
}
