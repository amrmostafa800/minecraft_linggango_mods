package com.linggango.sync.net;

import java.util.Objects;

/**
 * A player's position, used to decide whether a machine update is worth sending.
 *
 * <p>This is deliberately a plain record with no Minecraft dependency, so interest rules can
 * be unit tested without a server.
 */
public record ViewerPosition(String player, double x, double y, double z) {

    public ViewerPosition {
        Objects.requireNonNull(player, "player");
    }

    public double distanceSquaredTo(double ox, double oy, double oz) {
        double dx = x - ox;
        double dy = y - oy;
        double dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }

    public boolean within(double ox, double oy, double oz, double range) {
        return distanceSquaredTo(ox, oy, oz) <= range * range;
    }
}
