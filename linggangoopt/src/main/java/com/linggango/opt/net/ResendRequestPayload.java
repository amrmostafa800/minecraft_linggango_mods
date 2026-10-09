package com.linggango.opt.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client to server. Sent when a client could not replay a payload the server skipped (corrupt or
 * evicted cache, or a memory failure while rebuilding the packet). The server answers by resending
 * the real payload, so a client is never left without datapack data. This is the safety valve that
 * makes skipping fail-closed: the only cost of a bad cache is a normal resend.
 */
public final class ResendRequestPayload {

    public final String channel;

    public ResendRequestPayload(String channel) {
        this.channel = channel;
    }

    public static void encode(ResendRequestPayload msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.channel);
    }

    public static ResendRequestPayload decode(FriendlyByteBuf buf) {
        return new ResendRequestPayload(buf.readUtf());
    }

    public static void handle(ResendRequestPayload msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DatapackServerHooks.onResendRequest(context, msg));
        context.setPacketHandled(true);
    }
}
