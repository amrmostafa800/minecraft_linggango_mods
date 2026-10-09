package com.linggango.sync.net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interest management: decides which players are close enough to care about an object.
 *
 * <p>Phase 1 established that vanilla entity/block tracking is not the whole story in
 * Linggango. Several mods push their own packets to every player in the dimension, or to the
 * whole server, regardless of distance. Those are exactly the packets that this mod can
 * suppress, because they are the ones carrying information a distant player does not need.
 *
 * <p>Three tiers are supported:
 * <ul>
 *   <li>{@link Tier#IMMEDIATE} — the player is near the object or is watching it. Updates
 *       are sent as they happen.</li>
 *   <li>{@link Tier#DEFERRED} — the player is far away. Updates are dropped; the player gets
 *       a full snapshot when they come back into range.</li>
 *   <li>{@link Tier#IGNORED} — the player is in another dimension or has the data muted.</li>
 * </ul>
 *
 * <p>Fail-closed: a player with no known position is treated as {@link Tier#IMMEDIATE}, so
 * an unknown always results in a normal, unoptimised send.
 */
public final class InterestManager {

    public enum Tier {
        IMMEDIATE,
        DEFERRED,
        IGNORED
    }

    private final Map<String, ViewerPosition> positions = new ConcurrentHashMap<>();
    private final double immediateRange;
    private final double deferRange;
    private final Map<String, String> dimensionOfPlayer = new ConcurrentHashMap<>();

    /**
     * @param immediateRange within this distance, updates are sent immediately
     * @param deferRange     within this distance, updates are dropped but the player keeps a
     *                       baseline so it can be refreshed on approach
     */
    public InterestManager(double immediateRange, double deferRange) {
        this.immediateRange = Math.max(0.0, immediateRange);
        this.deferRange = Math.max(this.immediateRange, deferRange);
    }

    public void track(ViewerPosition position) {
        positions.put(position.player(), position);
    }

    public void trackDimension(String player, String dimension) {
        if (dimension == null) {
            dimensionOfPlayer.remove(player);
        } else {
            dimensionOfPlayer.put(player, dimension);
        }
    }

    public void forget(String player) {
        positions.remove(player);
        dimensionOfPlayer.remove(player);
    }

    public ViewerPosition positionOf(String player) {
        return positions.get(player);
    }

    public Tier tierFor(String player, String objectDimension, double x, double y, double z) {
        if (objectDimension != null) {
            String playerDimension = dimensionOfPlayer.get(player);
            if (playerDimension != null && !playerDimension.equals(objectDimension)) {
                return Tier.IGNORED;
            }
        }
        ViewerPosition position = positions.get(player);
        if (position == null) {
            // Unknown position: behave as if the player is standing on the object.
            return Tier.IMMEDIATE;
        }
        double distanceSquared = position.distanceSquaredTo(x, y, z);
        if (distanceSquared <= immediateRange * immediateRange) {
            return Tier.IMMEDIATE;
        }
        if (distanceSquared <= deferRange * deferRange) {
            return Tier.DEFERRED;
        }
        return Tier.IGNORED;
    }

    /** Players who should receive an update about an object right now. */
    public List<String> interestedPlayers(String objectDimension, double x, double y, double z) {
        List<String> interested = new ArrayList<>();
        for (String player : positions.keySet()) {
            if (tierFor(player, objectDimension, x, y, z) == Tier.IMMEDIATE) {
                interested.add(player);
            }
        }
        return interested;
    }

    /** Players who were previously near an object and now need a refresh snapshot. */
    public List<String> newlyInRange(Collection<String> candidates, String objectDimension,
                                     double x, double y, double z) {
        List<String> out = new ArrayList<>();
        for (String player : candidates) {
            if (tierFor(player, objectDimension, x, y, z) == Tier.IMMEDIATE) {
                out.add(player);
            }
        }
        return out;
    }

    public double immediateRange() {
        return immediateRange;
    }

    public double deferRange() {
        return deferRange;
    }
}
