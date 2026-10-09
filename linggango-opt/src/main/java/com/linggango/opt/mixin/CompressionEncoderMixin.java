package com.linggango.opt.mixin;

import com.linggango.opt.bench.WireStats;
import com.linggango.opt.config.OptConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures the true post-compression frame size.
 *
 * <p>Injects at the head of the vanilla encoder purely to read the input length, then at the tail to
 * read how many bytes were actually written. It changes nothing; a failure only costs a
 * measurement, never a packet.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {

    @Inject(method = "encode", at = @At("HEAD"))
    private void linggangoopt$raw(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        if (OptConfig.COMMON.wireMonitor.get()) {
            linggangoopt$rawLength.set(in.readableBytes());
        }
    }

    @Inject(method = "encode", at = @At("TAIL"))
    private void linggangoopt$written(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        if (!OptConfig.COMMON.wireMonitor.get()) {
            return;
        }
        Integer raw = linggangoopt$rawLength.get();
        linggangoopt$rawLength.remove();
        WireStats.record(raw == null ? 0 : raw, out.readableBytes());
    }

    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Integer> linggangoopt$rawLength = new ThreadLocal<>();
}
