package com.linggango.rtp;

import java.util.Random;

/**
 * Picks a random candidate column for the {@code /rtp} search. Pure and
 * deterministic given the supplied {@link Random}, so it is unit-testable
 * without any Minecraft classes.
 */
public final class CandidatePicker {

    private CandidatePicker() {
    }

    /**
     * Draws a point uniformly by <em>area</em> in the annulus between
     * {@code minRadius} and {@code maxRadius} around {@code (centerX, centerZ)}.
     * Sampling {@code r = sqrt(uniform(minR^2, maxR^2))} makes every unit of area
     * equally likely, unlike a radius-uniform draw which clusters near the
     * center.
     *
     * <p>When the world border is finite ({@code borderHalfSize > 0}) each axis
     * is clamped into {@code [-borderHalfSize, +borderHalfSize]}; a
     * {@code borderHalfSize <= 0} means an infinite border and no clamping.
     *
     * @return a fresh {@code int[]{x, z}}
     */
    public static int[] pick(Random rng, int centerX, int centerZ, int minRadius, int maxRadius,
                             double borderHalfSize) {
        double minR2 = (double) minRadius * minRadius;
        double maxR2 = (double) maxRadius * maxRadius;
        double r = Math.sqrt(minR2 + rng.nextDouble() * (maxR2 - minR2));
        double theta = rng.nextDouble() * 2.0 * Math.PI;

        int x = centerX + (int) Math.round(Math.cos(theta) * r);
        int z = centerZ + (int) Math.round(Math.sin(theta) * r);

        if (borderHalfSize > 0) {
            x = clamp(x, borderHalfSize);
            z = clamp(z, borderHalfSize);
        }
        return new int[]{x, z};
    }

    private static int clamp(int value, double borderHalfSize) {
        if (value < -borderHalfSize) {
            return (int) Math.ceil(-borderHalfSize);
        }
        if (value > borderHalfSize) {
            return (int) Math.floor(borderHalfSize);
        }
        return value;
    }
}
