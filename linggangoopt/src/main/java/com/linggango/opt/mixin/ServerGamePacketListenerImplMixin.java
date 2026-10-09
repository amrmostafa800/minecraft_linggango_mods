package com.linggango.opt.mixin;

import com.linggango.opt.config.OptConfig;
import com.linggango.opt.net.DatapackCache;
import com.linggango.opt.net.DatapackServerHooks;
import com.linggango.opt.net.HelloPayload;
import com.linggango.opt.net.OptNetwork;
import com.linggango.opt.net.OptRuntime;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateTagsPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opt-in: drops a datapack payload when the returning player already holds byte-identical data.
 *
 * <p>The send is cancelled only when the player's persisted claim hashes to the same value as the
 * packet the server is about to send, so a stale or forged claim never suppresses the send. When
 * cancelled, a tiny {@code Hello} tells the client which channel to replay from cache; the client
 * verifies the hash again before replaying, so nothing is ever trusted blindly. Off by default.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void linggangoopt$maybeSkip(Packet<?> packet, CallbackInfo ci) {
        if (!OptConfig.COMMON.skipServerSendOnHit.get() || !OptRuntime.serverHooksOk()) {
            return;
        }
        if (!OptConfig.COMMON.verifyHash.get() || player == null) {
            return;
        }
        DatapackCache.Channel channel;
        if (packet instanceof ClientboundUpdateRecipesPacket && OptConfig.COMMON.cacheRecipes.get()) {
            channel = DatapackCache.Channel.RECIPES;
        } else if (packet instanceof ClientboundUpdateTagsPacket && OptConfig.COMMON.cacheTags.get()) {
            channel = DatapackCache.Channel.TAGS;
        } else {
            return;
        }
        try {
            String actual = DatapackServerHooks.hashOf(packet, channel);
            boolean skip = DatapackServerHooks.canSkip(player.getUUID(), channel, actual);
            if (OptConfig.COMMON.debug.get()) {
                com.linggango.opt.LinggangoOptimizer.LOGGER.info(
                        "[opt] send {} actual={} skip={}", channel.id(), actual.substring(0, 8), skip);
            }
            if (skip) {
                OptNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new HelloPayload(channel.id(), actual));
                ci.cancel();
            }
        } catch (Throwable t) {
            OptRuntime.failServerHook("send-skip", t);
        }
    }
}
