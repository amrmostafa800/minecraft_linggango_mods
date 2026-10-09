package com.linggango.opt;

import com.linggango.opt.config.OptConfig;
import com.linggango.opt.net.CommandEvents;
import com.linggango.opt.net.OptNetwork;
import com.linggango.opt.net.OptRuntime;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

/**
 * Linggango Optimizer — a second, complementary pass over the pack's networking.
 *
 * <p>The first mod ({@code linggangosync}) answers "which data <em>needs</em> to be synchronised
 * at all". This one answers two narrower, lower-risk questions:
 *
 * <ol>
 *   <li>Which data is <em>re-sent identically to every player</em>, and can therefore be cached
 *       on the client and replayed locally on a reconnect? The datapack payloads
 *       (recipes, tags) are unconditional, byte-identical for every client, and are exactly the
 *       kind of information the brief calls "already available from installed mods".</li>
 *   <li>What is the <em>real</em> wire cost, after zlib? The first mod's monitor reads the buffer
 *       at the packet encoder, which runs before compression; its numbers are a useful but
 *       upper-bound signal. This mod measures at the compression stage for ground truth.</li>
 * </ol>
 *
 * <p>Everything here fails closed: any exception in a hook disables that hook for the session and
 * leaves vanilla behaviour untouched.
 */
@Mod(LinggangoOptimizer.MOD_ID)
public final class LinggangoOptimizer {

    public static final String MOD_ID = "linggangoopt";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LinggangoOptimizer() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, OptConfig.SPEC, "linggangoopt-common.toml");
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            OptNetwork.register();
            OptRuntime.init();
            CommandEvents.register();
        });
    }

    private void onServerStopping(ServerStoppingEvent event) {
        OptRuntime.onServerStopping();
    }
}
