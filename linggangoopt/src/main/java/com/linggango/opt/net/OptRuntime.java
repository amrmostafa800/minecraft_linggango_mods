package com.linggango.opt.net;

import com.linggango.opt.LinggangoOptimizer;
import com.linggango.opt.config.OptConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared, side-aware runtime state.
 *
 * <p>Every hook reads {@link #active()}; when anything goes wrong it disables that side's hooks
 * for the session so the pack falls back to vanilla synchronisation.
 */
public final class OptRuntime {

    private static final AtomicBoolean active = new AtomicBoolean(false);
    private static final AtomicBoolean serverHooksFailed = new AtomicBoolean(false);
    private static final AtomicBoolean clientHooksFailed = new AtomicBoolean(false);

    private static DatapackCache clientCache;
    private static ServerClaims serverClaims;
    private static Path clientCacheFile;
    private static Path serverClaimsFile;
    private static volatile boolean client;
    private static volatile boolean server;
    private static final java.util.concurrent.ConcurrentHashMap<DatapackCache.Channel, String> pendingClaims =
            new java.util.concurrent.ConcurrentHashMap<>();

    private OptRuntime() {
    }

    public static void init() {
        client = FMLEnvironment.dist.isClient();
        server = FMLEnvironment.dist.isDedicatedServer();
        if (!OptConfig.COMMON.enabled.get()) {
            active.set(false);
            LinggangoOptimizer.LOGGER.info("[opt] disabled by config; vanilla synchronisation in use");
            return;
        }
        active.set(true);
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            if (client) {
                Path dir = gameDir.resolve("linggangoopt");
                clientCacheFile = dir.resolve("datapacks.bin");
                clientCache = DatapackCache.load(clientCacheFile);
                LinggangoOptimizer.LOGGER.info("[opt] client datapack cache: {} payload(s) from {}",
                        clientCache.snapshot().size(), clientCacheFile);
            }
            if (server) {
                Path dir = gameDir.resolve("linggangoopt");
                serverClaimsFile = dir.resolve("claims.bin");
                serverClaims = ServerClaims.load(serverClaimsFile);
                LinggangoOptimizer.LOGGER.info("[opt] server claim store loaded from {}", serverClaimsFile);
            }
        } catch (Throwable t) {
            LinggangoOptimizer.LOGGER.warn("[opt] init failed; optimisations disabled", t);
            failClosed();
        }
    }

    public static boolean isClient() {
        return client;
    }

    public static boolean isServer() {
        return server;
    }

    public static boolean active() {
        return active.get();
    }

    public static boolean serverHooksOk() {
        return active.get() && server && !serverHooksFailed.get();
    }

    public static boolean clientHooksOk() {
        return active.get() && client && !clientHooksFailed.get();
    }

    public static DatapackCache cache() {
        return clientCache;
    }

    public static ServerClaims claims() {
        return serverClaims;
    }

    public static Path clientCacheFile() {
        return clientCacheFile;
    }

    public static Path serverClaimsFile() {
        return serverClaimsFile;
    }

    public static void failClosed() {
        active.set(false);
    }

    public static void failServerHook(String what, Throwable t) {
        if (serverHooksFailed.compareAndSet(false, true)) {
            LinggangoOptimizer.LOGGER.warn("[opt] server hook '{}' failed; reverting to vanilla sync", what, t);
        }
    }

    public static void failClientHook(String what, Throwable t) {
        if (clientHooksFailed.compareAndSet(false, true)) {
            LinggangoOptimizer.LOGGER.warn("[opt] client hook '{}' failed; reverting to vanilla handling", what, t);
        }
    }

    /** Remember a claim to be sent from the client tick, once the connection exists. */
    public static void queueClaim(DatapackCache.Channel channel, String hash) {
        if (hash != null) {
            pendingClaims.put(channel, hash);
        }
    }

    /**
     * Called every client tick. Claims are retained until the connection exists, so a claim queued
     * during login is not lost if a tick fires during login is not lost.
     */
    public static void drainClientWork() {
        if (!clientHooksOk() || pendingClaims.isEmpty()) {
            return;
        }
        // Wait until the local player exists: sending during login races the play-phase channel and
        // the claim is silently dropped. Claims are retained until then.
        if (net.minecraft.client.Minecraft.getInstance().player == null) {
            return;
        }
        for (DatapackCache.Channel channel : DatapackCache.Channel.values()) {
            String hash = pendingClaims.remove(channel);
            if (hash == null) {
                continue;
            }
            try {
                OptNetwork.CHANNEL.sendToServer(new ClaimPayload(channel.id(), hash));
                if (OptConfig.COMMON.debug.get()) {
                    LinggangoOptimizer.LOGGER.info("[opt] sent claim {} hash={}", channel.id(),
                            hash.substring(0, Math.min(8, hash.length())));
                }
            } catch (Throwable t) {
                // Best-effort: on failure we simply resend the payload next login.
                LinggangoOptimizer.LOGGER.warn("[opt] claim send for {} failed: {}", channel.id(), String.valueOf(t));
            }
        }
    }

    public static void flushClientCache() {
        if (clientCache != null && clientCacheFile != null) {
            clientCache.store(clientCacheFile);
        }
    }

    public static void onServerStopping() {
        if (serverClaims != null && serverClaimsFile != null) {
            serverClaims.store(serverClaimsFile);
        }
    }

    public static void saveServerClaims() {
        if (serverClaims != null && serverClaimsFile != null) {
            serverClaims.store(serverClaimsFile);
        }
    }

}
