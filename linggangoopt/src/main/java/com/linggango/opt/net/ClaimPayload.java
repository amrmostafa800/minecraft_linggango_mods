package com.linggango.opt.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server. The client states the hash and version it already holds for a datapack
 * channel. The server uses this only to decide whether the payload must be sent; the claim is
 * never trusted as data, only as a "please don't send bytes I have" hint, and only honoured when
 * the hash matches the server's freshly computed hash.
 */
public final class ClaimPayload {

    public final String channel;
    public final String hash;

    public ClaimPayload(String channel, String hash) {
        this.channel = channel;
        this.hash = hash;
    }

    public static void encode(ClaimPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.channel);
        buf.writeUtf(msg.hash);
    }

    public static ClaimPayload decode(FriendlyByteBuf buf) {
        return new ClaimPayload(buf.readUtf(), buf.readUtf());
    }

    public static void handle(ClaimPayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DatapackServerHooks.onClaim(context, msg));
        context.setPacketHandled(true);
    }
}
