package com.linggango.opt.mixin;

import com.linggango.opt.net.DatapackCache;
import com.linggango.opt.net.DatapackClientHooks;
import com.linggango.opt.net.Hashes;
import com.linggango.opt.net.OptRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Captures the exact wire bytes of the recipes and tags payloads on the client.
 *
 * <p>Hooks the decoder head, before vanilla consumes the frame. The frame is {@code [id varint]} +
 * {@code [payload]}, and the payload is byte-for-byte what {@code ClientboundUpdateRecipesPacket
 * .write} produced on the server, so the captured bytes hash to the same value the server computes
 * and can be replayed straight back into the packet constructor. Copying uses {@code getBytes} and
 * never advances the reader index, so decoding is unaffected.
 */
@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin {

    private static volatile Int2ObjectMap<Class<? extends net.minecraft.network.protocol.Packet<?>>> linggangoopt$ids;
    private static volatile int linggangoopt$recipesId = -2;
    private static volatile int linggangoopt$tagsId = -2;

    @Inject(method = "decode", at = @At("HEAD"))
    private void linggangoopt$capture(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        if (!OptRuntime.clientHooksOk()) {
            return;
        }
        try {
            int idx = in.readerIndex();
            int id = linggangoopt$readVarInt(in, idx);
            if (id < 0) {
                return;
            }
            linggangoopt$resolveIds();
            DatapackCache.Channel channel;
            if (id == linggangoopt$recipesId) {
                channel = DatapackCache.Channel.RECIPES;
            } else if (id == linggangoopt$tagsId) {
                channel = DatapackCache.Channel.TAGS;
            } else {
                return;
            }
            int payloadStart = idx + linggangoopt$varIntSize(in, idx);
            int len = in.writerIndex() - payloadStart;
            if (len <= 0) {
                return;
            }
            byte[] bytes = new byte[len];
            in.getBytes(payloadStart, bytes);
            DatapackClientHooks.capture(channel, bytes, Hashes.sha256(bytes));
        } catch (Throwable t) {
            OptRuntime.failClientHook("datapack-capture", t);
        }
    }

    private static int linggangoopt$readVarInt(ByteBuf in, int idx) {
        int value = 0;
        int size = 0;
        byte b;
        do {
            b = in.getByte(idx + size);
            value |= (b & 0x7F) << (size * 7);
            size++;
            if (size > 5) {
                return -1;
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    private static int linggangoopt$varIntSize(ByteBuf in, int idx) {
        int size = 0;
        byte b;
        do {
            b = in.getByte(idx + size);
            size++;
        } while ((b & 0x80) != 0 && size < 5);
        return size;
    }

    /** Resolve the two packet ids once, against the public id table of the play protocol. */
    private static void linggangoopt$resolveIds() {
        if (linggangoopt$ids != null) {
            return;
        }
        synchronized (PacketDecoderMixin.class) {
            if (linggangoopt$ids != null) {
                return;
            }
            Int2ObjectMap<Class<? extends net.minecraft.network.protocol.Packet<?>>> map =
                    ConnectionProtocol.PLAY.getPacketsByIds(PacketFlow.CLIENTBOUND);
            int recipes = -1;
            int tags = -1;
            for (Int2ObjectMap.Entry<Class<? extends net.minecraft.network.protocol.Packet<?>>> e : map.int2ObjectEntrySet()) {
                if (e.getValue() == ClientboundUpdateRecipesPacket.class) {
                    recipes = e.getIntKey();
                } else if (e.getValue() == ClientboundUpdateTagsPacket.class) {
                    tags = e.getIntKey();
                }
            }
            linggangoopt$recipesId = recipes;
            linggangoopt$tagsId = tags;
            linggangoopt$ids = map;
        }
    }
}
