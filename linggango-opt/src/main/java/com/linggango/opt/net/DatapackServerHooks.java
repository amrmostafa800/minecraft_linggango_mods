package com.linggango.opt.net;

import com.linggango.opt.LinggangoOptimizer;
import com.linggango.opt.config.OptConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraftforge.network.NetworkEvent;
import io.netty.buffer.Unpooled;

import java.util.UUID;

/**
 * Server-side handling of datapack claims and the (opt-in) send skip.
 *
 * <p>A claim is never trusted as data. It is used for exactly one decision: whether the bytes the
 * server is about to send are the bytes the client already holds. The comparison is against the
 * hash the server computes from the live packet, so a stale or hostile claim simply does not
 * match and the payload is sent normally.
 */
public final class DatapackServerHooks {

    private DatapackServerHooks() {
    }

    /**
     * The client cannot replay a skipped payload; resend the real packet from authoritative state.
     * This runs on the server thread (the network handler enqueues it), so it re-reads whatever the
     * current state is and sends it directly to the player.
     */
    public static void onResendRequest(NetworkEvent.Context ctx, ResendRequestPayload msg) {
        if (!OptRuntime.serverHooksOk() || ctx.getSender() == null) {
            return;
        }
        try {
            DatapackCache.Channel channel = channelOf(msg.channel);
            net.minecraft.server.level.ServerPlayer player = ctx.getSender();
            net.minecraft.server.MinecraftServer server = player.getServer();
            if (channel == null || server == null) {
                return;
            }
            Packet<?> packet = switch (channel) {
                case RECIPES -> new net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket(
                        server.getRecipeManager().getRecipes());
                case TAGS -> new net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket(
                        net.minecraft.tags.TagNetworkSerialization.serializeTagsToNetwork(server.registries()));
            };
            player.connection.send(packet);
            // Clear the claim so a second failure cannot skip again: the client asked for the
            // real bytes, and it stays in vanilla mode until it positively acknowledges them.
            OptRuntime.claims().forget(player.getUUID(), channel);
            OptRuntime.saveServerClaims();
            LinggangoOptimizer.LOGGER.info("[opt] resent {} to {} after failed replay",
                    channel.id(), player.getName().getString());
        } catch (Throwable t) {
            OptRuntime.failServerHook("resend", t);
        }
    }

    public static void onClaim(NetworkEvent.Context ctx, ClaimPayload msg) {
        if (!OptRuntime.serverHooksOk()) {
            return;
        }
        try {
            DatapackCache.Channel channel = channelOf(msg.channel);
            if (channel == null || msg.hash == null || msg.hash.isEmpty() || ctx.getSender() == null) {
                return;
            }
            UUID player = ctx.getSender().getUUID();
            OptRuntime.claims().claim(player, channel, msg.hash);
            OptRuntime.saveServerClaims();
            if (OptConfig.COMMON.debug.get()) {
                LinggangoOptimizer.LOGGER.info("[opt] claim from {}: {} hash={}",
                        ctx.getSender().getName().getString(), msg.channel, msg.hash.substring(0, Math.min(8, msg.hash.length())));
            }
        } catch (Throwable t) {
            OptRuntime.failServerHook("claim", t);
        }
    }

    /**
     * Returns true when the payload about to be sent is byte-identical to what this player claimed,
     * meaning the send can be skipped. Only consulted when {@code skip_server_send_on_hash_hit} is on.
     */
    public static boolean canSkip(UUID player, DatapackCache.Channel channel, String actualHash) {
        if (!OptRuntime.serverHooksOk()) {
            return false;
        }
        try {
            ServerClaims claims = OptRuntime.claims();
            if (claims == null) {
                return false;
            }
            ServerClaims.Claim claim = claims.claim(player, channel);
            if (claim == null) {
                return false;
            }
            boolean hit = claim.hash().equals(actualHash);
            if (hit) {
                claims.recordSkip(player);
            }
            return hit;
        } catch (Throwable t) {
            OptRuntime.failServerHook("skip-hash", t);
            return false;
        }
    }

    /** Serialises the packet exactly as vanilla will and hashes the bytes. */
    public static String hashOf(Packet<?> packet, DatapackCache.Channel channel) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return Hashes.sha256(bytes);
        } finally {
            buf.release();
        }
    }

    private static DatapackCache.Channel channelOf(String id) {
        for (DatapackCache.Channel c : DatapackCache.Channel.values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
