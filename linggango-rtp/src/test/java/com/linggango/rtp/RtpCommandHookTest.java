package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers the pure interception decision in {@link RtpCommandHook}. The
 * {@code onCommand} handler needs a live Forge {@code CommandEvent} and is
 * exercised in-game; only the decision function, which is what the handler
 * defers to, is tested here.
 */
class RtpCommandHookTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void interceptsOverworldRtp() {
        assertTrue(RtpCommandHook.shouldIntercept("rtp", OVERWORLD, false, true, true, true),
                "an overworld /rtp with no cooldown, FTB present, FTB RTP enabled and the mod enabled must be intercepted");
    }

    @Test
    void ignoresNetherAndEnd() {
        assertFalse(RtpCommandHook.shouldIntercept("rtp", "minecraft:the_nether", false, true, true, true),
                "the search is overworld-only");
        assertFalse(RtpCommandHook.shouldIntercept("rtp", "minecraft:the_end", false, true, true, true),
                "the search is overworld-only");
    }

    @Test
    void ignoresWhenOnCooldown() {
        assertFalse(RtpCommandHook.shouldIntercept("rtp", OVERWORLD, true, true, true, true),
                "an active cooldown must be left to FTB so it can emit its message");
    }

    @Test
    void ignoresWhenFtbAbsentOrDisabled() {
        assertFalse(RtpCommandHook.shouldIntercept("rtp", OVERWORLD, false, false, true, true),
                "without FTB Essentials the mod must be a no-op");
        assertFalse(RtpCommandHook.shouldIntercept("rtp", OVERWORLD, false, true, true, false),
                "a disabled config must be a no-op");
    }

    @Test
    void ignoresWhenFtbRtpGateDisabled() {
        assertFalse(RtpCommandHook.shouldIntercept("rtp", OVERWORLD, false, true, false, true),
                "when FTB's own RTP.enabled gate is off, /rtp must fall through to FTB (which does not register it)");
    }

    @Test
    void ignoresOtherCommands() {
        assertFalse(RtpCommandHook.shouldIntercept("home", OVERWORLD, false, true, true, true),
                "only /rtp is intercepted");
    }
}
