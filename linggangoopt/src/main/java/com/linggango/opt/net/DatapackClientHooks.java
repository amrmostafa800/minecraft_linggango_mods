package com.linggango.opt.net;

import com.linggango.opt.LinggangoOptimizer;
import com.linggango.opt.config.OptConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;

/**
 * Client-side datapack capture and local replay.
 *
 * <p>Capture happens every time the vanilla datapack packet arrives, whether or not any saving
 * happened, so the cache is always the last thing the server actually sent. Replay happens only
 * when the server reports it skipped a payload <em>and</em> the cached bytes hash to the same value
 * the server was about to send. Replay goes through the real vanilla handler, so the resulting
 * client state is exactly what it would have been had the packet crossed the wire.
 */
public final class DatapackClientHooks {

    private DatapackClientHooks() {
    }

    /** Called from the packet mixin with the exact vanilla bytes of a datapack packet. */
    public static void capture(DatapackCache.Channel channel, byte[] bytes, String hash) {
        if (!OptRuntime.clientHooksOk() || OptRuntime.cache() == null) {
            return;
        }
        if (channel == DatapackCache.Channel.RECIPES && !OptConfig.COMMON.cacheRecipes.get()) {
            return;
        }
        if (channel == DatapackCache.Channel.TAGS && !OptConfig.COMMON.cacheTags.get()) {
            return;
        }
        OptRuntime.cache().put(channel, new DatapackCache.Entry(hash, bytes));
        if (OptConfig.COMMON.debug.get()) {
            LinggangoOptimizer.LOGGER.info("[opt] cached {} payload ({} bytes)", channel.id(), bytes.length);
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(OptRuntime::flushClientCache);
        // Deferred: a claim can only be sent once the connection exists, which for tags may be
        // during login when getConnection() is still null. Queue it; the client tick flushes it.
        OptRuntime.queueClaim(channel, hash);
    }

    /** Server skipped a payload; replay it from cache if our bytes match the server's hash. */
    public static void onHello(HelloPayload msg) {
        if (OptConfig.COMMON.debug.get()) {
            LinggangoOptimizer.LOGGER.info("[opt] hello received channel={} hash={}", msg.channel,
                    msg.hash.substring(0, Math.min(8, msg.hash.length())));
        }
        if (!OptRuntime.clientHooksOk() || OptRuntime.cache() == null) {
            return;
        }
        DatapackCache.Channel channel = channelOf(msg.channel);
        if (channel == null) {
            return;
        }
        DatapackCache.Entry entry = OptRuntime.cache().get(channel);
        if (entry == null || !entry.hash().equals(msg.hash)) {
            LinggangoOptimizer.LOGGER.warn(
                    "[opt] server skipped {} but local cache {}; requesting a resend",
                    msg.channel, entry == null ? "is empty" : "does not match");
            requestResend(channel);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) {
            tryReplay(mc, channel, entry);
        } else {
            mc.execute(() -> tryReplay(mc, channel, entry));
        }
    }

    /**
     * Rebuild the packet from cached bytes and hand it to the vanilla handler. If anything fails
     * (a corrupt cache, or a transient memory error while deserialising) we must not swallow it:
     * the server already withheld the payload, so we ask it to resend and leave client hooks
     * installed. A failed replay is never allowed to strand the client without recipe/tag data.
     */
    private static void tryReplay(Minecraft mc, DatapackCache.Channel channel, DatapackCache.Entry entry) {
        try {
            ClientPacketListener listener = mc.getConnection();
            if (listener == null) {
                requestResend(channel);
                return;
            }
            net.minecraft.network.FriendlyByteBuf buf =
                    new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(entry.data()));
            switch (channel) {
                case RECIPES -> listener.handleUpdateRecipes(new ClientboundUpdateRecipesPacket(buf));
                case TAGS -> listener.handleUpdateTags(new ClientboundUpdateTagsPacket(buf));
            }
            if (OptConfig.COMMON.debug.get()) {
                LinggangoOptimizer.LOGGER.info("[opt] replayed cached {} payload locally", channel.id());
            }
        } catch (Throwable t) {
            LinggangoOptimizer.LOGGER.warn("[opt] replay of cached {} failed ({}); requesting a resend",
                    channel.id(), t.toString());
            requestResend(channel);
        }
    }

    /** Ask the server to send the real payload; the server clears the claim so it cannot skip again. */
    private static void requestResend(DatapackCache.Channel channel) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            OptNetwork.CHANNEL.sendToServer(new ResendRequestPayload(channel.id()));
        } catch (Throwable t) {
            LinggangoOptimizer.LOGGER.warn("[opt] could not request resend of {}: {}", channel.id(),
                    String.valueOf(t));
        }
    }

    static DatapackCache.Channel channelOf(String id) {
        for (DatapackCache.Channel c : DatapackCache.Channel.values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
