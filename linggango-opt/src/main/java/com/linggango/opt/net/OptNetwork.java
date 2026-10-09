package com.linggango.opt.net;

import com.linggango.opt.LinggangoOptimizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The mod's own channel. It carries only tiny control messages used to decide whether a datapack
 * payload needs to cross the wire at all; it never carries the payload itself.
 */
public final class OptNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(LinggangoOptimizer.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private static int nextId = 0;

    private OptNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(nextId++, HelloPayload.class, HelloPayload::encode, HelloPayload::decode,
                HelloPayload::handle);
        CHANNEL.registerMessage(nextId++, ClaimPayload.class, ClaimPayload::encode, ClaimPayload::decode,
                ClaimPayload::handle);
        CHANNEL.registerMessage(nextId++, ResendRequestPayload.class, ResendRequestPayload::encode,
                ResendRequestPayload::decode, ResendRequestPayload::handle);
    }
}
