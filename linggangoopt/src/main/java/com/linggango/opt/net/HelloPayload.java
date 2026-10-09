package com.linggango.opt.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client. Sent only when the server has decided to skip a datapack payload because the
 * client's claim matched. It tells the client which channel to replay from cache, and the hash to
 * verify against, so a mismatched or stale cache is never replayed.
 */
public final class HelloPayload {

    public final String channel;
    public final String hash;

    public HelloPayload(String channel, String hash) {
        this.channel = channel;
        this.hash = hash;
    }

    public static void encode(HelloPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.channel);
        buf.writeUtf(msg.hash);
    }

    public static HelloPayload decode(FriendlyByteBuf buf) {
        return new HelloPayload(buf.readUtf(), buf.readUtf());
    }

    public static void handle(HelloPayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DatapackClientHooks.onHello(msg));
        context.setPacketHandled(true);
    }
}
