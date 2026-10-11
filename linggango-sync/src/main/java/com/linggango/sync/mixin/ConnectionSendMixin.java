package com.linggango.sync.mixin;

import com.linggango.sync.net.SyncRuntime;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.network.ICustomPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses an outbound custom payload that is byte-identical to the immediately
 * preceding payload sent to the same connection on the same channel.
 *
 * <p>The injection is at the head of the private {@code sendPacket}, which is the single
 * funnel every outbound packet passes through before {@code writeAndFlush}. Cancelling
 * there means the packet is never encoded, so no bytes — and no zero-length frame — reach
 * the client.
 *
 * <p>Only Forge custom payloads ({@link ICustomPacket}) are candidates, and only in the
 * PLAY phase; vanilla and login/config traffic is never touched. A payload carrying a
 * {@link PacketSendListener} is never suppressed, because cancelling would skip the
 * listener the sender attached.
 *
 * <p>The feature is off by default. When off, the injected method reads one flag and
 * returns.
 */
@Mixin(net.minecraft.network.Connection.class)
public abstract class ConnectionSendMixin {

    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void linggangosync$suppressDuplicate(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (!SyncRuntime.config().suppressDuplicates() || listener != null) {
            return;
        }
        try {
            if (!(packet instanceof ICustomPacket<?> custom)) {
                return;
            }
            Connection connection = (Connection) (Object) this;
            if (connection.getSending() != PacketFlow.CLIENTBOUND) {
                return;
            }
            String channel = custom.getName() == null ? null : custom.getName().toString();
            if (channel == null || channel.startsWith("minecraft:") || channel.startsWith("forge:")) {
                return;
            }
            ByteBuf data = custom.getInternalData();
            if (data == null) {
                return;
            }
            int size = data.readableBytes();
            byte[] payload = new byte[size];
            data.getBytes(data.readerIndex(), payload);
            if (SyncRuntime.suppressor().shouldSuppress(connection, channel, custom.getIndex(), payload)) {
                SyncRuntime.recordSuppressed(size);
                ci.cancel();
            }
        } catch (Throwable t) {
            // Never break a connection over an optimisation: on any doubt, send normally.
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void linggangosync$forgetOnClose(io.netty.channel.ChannelHandlerContext ctx, CallbackInfo ci) {
        try {
            Object connection = this;
            SyncRuntime.suppressor().forget(connection);
            com.linggango.sync.net.PlayerNames.forget(connection);
        } catch (Throwable t) {
            // Cleanup failure must never affect the connection teardown.
        }
    }
}
