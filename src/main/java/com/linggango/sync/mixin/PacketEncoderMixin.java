package com.linggango.sync.mixin;

import com.linggango.sync.net.NetworkMonitor;
import com.linggango.sync.net.PacketAttribution;
import com.linggango.sync.net.PacketDirection;
import com.linggango.sync.net.PacketKind;
import com.linggango.sync.net.PacketSample;
import com.linggango.sync.net.PlayerNames;
import com.linggango.sync.net.SyncRuntime;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.network.ICustomPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures every outbound packet at the exact point its encoded size is known.
 *
 * <p>This is observation only. The mixin never modifies, reorders, drops or rewrites a
 * packet, and it always calls through to the original encoder. When monitoring is
 * disabled the injected method returns immediately after a single boolean read, so the
 * cost is one field access per packet.
 *
 * <p>The size is read from the {@code ByteBuf} the vanilla encoder produced, which means
 * it is the real post-compression, post-encryption wire size rather than an estimate.
 *
 * <p>Remapping is left enabled so MixinGradle translates the official names used here into
 * the SRG names the production runtime uses.
 */
@Mixin(net.minecraft.network.PacketEncoder.class)
public abstract class PacketEncoderMixin {

    @Shadow
    @Final
    private PacketFlow flow;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL"),
            require = 0,
            expect = 1)
    private void linggangosync$recordEncoded(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        if (!SyncRuntime.monitoring()) {
            return;
        }
        try {
            int size = out.readableBytes();
            if (size <= 0) {
                return;
            }
            Object connection = ctx.channel().pipeline().get("packet_handler");
            Object listener = null;
            if (connection instanceof Connection mcConnection) {
                listener = mcConnection.getPacketListener();
            }
            String channel = null;
            if (packet instanceof ICustomPacket<?> custom) {
                var name = custom.getName();
                channel = name == null ? null : name.toString();
            }
            String className = packet.getClass().getName();
            String mod = channel != null
                    ? PacketAttribution.fromChannel(channel)
                    : PacketAttribution.fromClassName(className);
            String player = PlayerNames.resolve(connection, listener);

            PacketDirection direction = flow == PacketFlow.CLIENTBOUND
                    ? PacketDirection.SERVER_TO_CLIENT
                    : PacketDirection.CLIENT_TO_SERVER;

            NetworkMonitor monitor = SyncRuntime.monitor();
            PacketSample sample = new PacketSample(
                    System.currentTimeMillis(),
                    direction,
                    PacketKind.ofClassName(className),
                    mod,
                    channel != null ? "CustomPayload" : packet.getClass().getSimpleName(),
                    channel,
                    size,
                    player);
            monitor.record(sample);
            if (SyncRuntime.config().logPerPacket()) {
                com.linggango.sync.LinggangoSync.LOGGER.info("[net] {}", sample.describe());
            }
        } catch (Throwable t) {
            // Measurement must never break the connection. Fail silently, once.
            SyncRuntime.setMonitoring(false);
            com.linggango.sync.LinggangoSync.LOGGER.warn("Packet monitoring disabled after an error", t);
        }
    }
}
