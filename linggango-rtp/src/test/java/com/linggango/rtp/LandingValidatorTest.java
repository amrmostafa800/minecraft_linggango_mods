package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure part of {@link LandingValidator}: the biome exclusion lookup.
 * {@code RtpConfig.excludedBiomes()} returns its shipped defaults when Forge has
 * not loaded the config (which is the case under plain JUnit), so these
 * assertions describe the real production default set. The world and reflective
 * paths ({@code findSafeY}, {@code claimedByOtherTeam}) need a live Minecraft
 * bootstrap and FTB Chunks on the classpath, so they are not unit-tested here.
 */
class LandingValidatorTest {

    @Test
    void excludesOceansAndRivers() {
        assertTrue(LandingValidator.biomeExcluded(rl("minecraft:ocean")),
                "oceans must be excluded by default");
        assertTrue(LandingValidator.biomeExcluded(rl("minecraft:river")),
                "rivers must be excluded by default");
    }

    @Test
    void excludesDeepDarkAndMushroom() {
        assertTrue(LandingValidator.biomeExcluded(rl("minecraft:deep_dark")),
                "the Deep Dark must be excluded by default");
        assertTrue(LandingValidator.biomeExcluded(rl("minecraft:mushroom_fields")),
                "Mushroom Fields must be excluded by default");
    }

    @Test
    void allowsNormalBiome() {
        assertFalse(LandingValidator.biomeExcluded(rl("minecraft:plains")),
                "plains is a valid landing biome");
    }

    private static ResourceLocation rl(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            throw new IllegalArgumentException("bad test id: " + id);
        }
        return location;
    }
}
