package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Covers the pure {@link CandidatePicker#pick} geometry: uniform-by-area radius
 * sampling, the per-axis world-border clamp, and the minimum-radius floor. Every
 * test supplies its own fixed-seed {@link Random}, so the draws are deterministic
 * and the assertions describe real sampling behaviour rather than a single value.
 */
class CandidatePickerTest {

    private static final int DRAWS = 10_000;

    @Test
    void uniformByAreaWithinBounds() {
        Random rng = new Random(20261010L);
        int centerX = 1000;
        int centerZ = -2000;
        int minRadius = 1000;
        int maxRadius = 32000;
        double beyondThreshold = 0.707 * maxRadius;

        int beyond = 0;
        for (int i = 0; i < DRAWS; i++) {
            int[] p = CandidatePicker.pick(rng, centerX, centerZ, minRadius, maxRadius, 0);
            double r = Math.hypot(p[0] - centerX, p[1] - centerZ);

            assertTrue(r >= minRadius - 1.0, "radius below min after rounding: " + r);
            assertTrue(r <= maxRadius + 1.0, "radius above max after rounding: " + r);
            if (r > beyondThreshold) {
                beyond++;
            }
        }

        // Area-uniform: P(r > 0.707*max) ~= 0.5, whereas radius-uniform would put
        // only ~0.293 beyond. The threshold cleanly separates the two.
        assertTrue(beyond > DRAWS * 0.4,
                "area-uniform sampling must place well over 40% beyond 0.707*max, got " + beyond);
    }

    @Test
    void clampsToBorder() {
        Random rng = new Random(4242L);
        double borderHalfSize = 1000.0;
        int maxRadius = 32000;

        for (int i = 0; i < DRAWS; i++) {
            int[] p = CandidatePicker.pick(rng, 0, 0, 0, maxRadius, borderHalfSize);
            assertTrue(Math.abs(p[0]) <= borderHalfSize, "x outside border: " + p[0]);
            assertTrue(Math.abs(p[1]) <= borderHalfSize, "z outside border: " + p[1]);
        }
    }

    @Test
    void minRadiusRespected() {
        Random rng = new Random(7L);
        int minRadius = 500;
        int maxRadius = 32000;

        for (int i = 0; i < DRAWS; i++) {
            int[] p = CandidatePicker.pick(rng, 0, 0, minRadius, maxRadius, 0);
            double r = Math.hypot(p[0], p[1]);
            assertTrue(r >= minRadius - 1.0, "radius below min: " + r);
        }
    }
}
