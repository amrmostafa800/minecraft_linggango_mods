package com.linggango.sync.net;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers interest management: the rule that decides whether a machine update is sent,
 * deferred until the player approaches, or dropped entirely.
 */
class InterestManagerTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void nearbyPlayerIsImmediate() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("alice", 10, 64, 10));

        assertEquals(InterestManager.Tier.IMMEDIATE, interest.tierFor("alice", OVERWORLD, 20, 64, 20));
    }

    @Test
    void distantPlayerIsNotInterested() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("far", 10_000, 64, 10_000));

        assertEquals(InterestManager.Tier.IGNORED, interest.tierFor("far", OVERWORLD, 0, 64, 0));
    }

    @Test
    void middleBandIsDeferredNotDropped() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        // 128 blocks away: past the immediate range, inside the defer range.
        interest.track(new ViewerPosition("bob", 128, 64, 0));

        assertEquals(InterestManager.Tier.DEFERRED, interest.tierFor("bob", OVERWORLD, 0, 64, 0));
    }

    @Test
    void playerInAnotherDimensionIsIgnored() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("alice", 0, 64, 0));
        interest.trackDimension("alice", "minecraft:the_nether");

        assertEquals(InterestManager.Tier.IGNORED, interest.tierFor("alice", OVERWORLD, 0, 64, 0));
    }

    @Test
    void unknownPlayerFailsClosedToImmediate() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        // No position recorded: the safe behaviour is a normal, unoptimised send.
        assertEquals(InterestManager.Tier.IMMEDIATE, interest.tierFor("stranger", OVERWORLD, 0, 64, 0));
    }

    @Test
    void unknownDimensionFailsClosedToImmediate() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("alice", 0, 64, 0));
        // Dimension not yet recorded: do not drop the update.
        assertEquals(InterestManager.Tier.IMMEDIATE, interest.tierFor("alice", null, 0, 64, 0));
    }

    @Test
    void interestedPlayersExcludesDistantOnes() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("near", 10, 64, 0));
        interest.track(new ViewerPosition("mid", 128, 64, 0));
        interest.track(new ViewerPosition("far", 10_000, 64, 0));

        List<String> interested = interest.interestedPlayers(OVERWORLD, 0, 64, 0);
        assertEquals(List.of("near"), interested);
    }

    @Test
    void playerApproachingBecomesInterestedAgain() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("alice", 10_000, 64, 0));
        assertTrue(interest.interestedPlayers(OVERWORLD, 0, 64, 0).isEmpty());

        // The player walks up to the machine; the next snapshot will reach them.
        interest.track(new ViewerPosition("alice", 20, 64, 0));
        assertEquals(List.of("alice"), interest.interestedPlayers(OVERWORLD, 0, 64, 0));
    }

    @Test
    void forgetStopsTracking() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("alice", 10_000, 64, 0));
        interest.forget("alice");
        assertTrue(interest.interestedPlayers(OVERWORLD, 0, 64, 0).isEmpty());
    }

    @Test
    void rangeBoundariesAreInclusive() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("edge", 64, 64, 0));
        assertEquals(InterestManager.Tier.IMMEDIATE, interest.tierFor("edge", OVERWORLD, 0, 64, 0));

        interest.track(new ViewerPosition("justOutside", 64.001, 64, 0));
        assertEquals(InterestManager.Tier.DEFERRED, interest.tierFor("justOutside", OVERWORLD, 0, 64, 0));
    }

    @Test
    void deferRangeCannotBeSmallerThanImmediateRange() {
        InterestManager interest = new InterestManager(256.0, 64.0);
        assertEquals(256.0, interest.deferRange());
    }

    @Test
    void newlyInRangeFiltersCandidates() {
        InterestManager interest = new InterestManager(64.0, 256.0);
        interest.track(new ViewerPosition("near", 5, 64, 0));
        interest.track(new ViewerPosition("far", 5_000, 64, 0));

        assertEquals(List.of("near"), interest.newlyInRange(List.of("near", "far"), OVERWORLD, 0, 64, 0));
    }
}
