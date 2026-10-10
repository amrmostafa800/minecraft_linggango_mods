package com.linggango.rtp;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LinggangoRtp.MODID)
public class LinggangoRtp {
    public static final String MODID = "linggango_rtp";
    public static final Logger LOGGER = LoggerFactory.getLogger("linggango-rtp");

    public LinggangoRtp() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, RtpConfig.SPEC,
                "linggango-rtp-common.toml");
        LOGGER.info("[rtp] loaded");
    }
}
