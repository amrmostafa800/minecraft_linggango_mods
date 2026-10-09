package com.linggango.opt.client;

import com.linggango.opt.LinggangoOptimizer;
import com.linggango.opt.net.OptRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only tick driver. Registered with {@code Dist.CLIENT} so the dedicated server never loads
 * it, keeping the client-only classes off the server classpath.
 */
@Mod.EventBusSubscriber(modid = LinggangoOptimizer.MOD_ID, value = Dist.CLIENT)
public final class ClientTickHandler {

    private ClientTickHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            OptRuntime.drainClientWork();
        }
    }
}
