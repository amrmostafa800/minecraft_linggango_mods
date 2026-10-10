package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the single-flight registry {@link RtpSearch} keeps for in-flight
 * searches, through the package-private seams. The asynchronous search itself
 * (candidate picking, the chunk future, validation and the teleport) needs a
 * live server and is not exercised here; only the registry that guarantees a
 * player cannot start two searches and that every exit path releases the entry
 * is tested.
 */
class RtpSearchTest {

    @AfterEach
    void clearRegistry() {
        RtpSearch.cancelAll();
    }

    @Test
    void secondStartIsRefused() {
        UUID player = UUID.randomUUID();

        assertFalse(RtpSearch.isPending(player), "no search should be pending initially");

        RtpSearch.markPending(player);

        assertTrue(RtpSearch.isPending(player),
                "a pending search must be visible, so a second start is refused");
    }

    @Test
    void cancelClearsPending() {
        UUID player = UUID.randomUUID();

        RtpSearch.markPending(player);
        assertEquals(1, RtpSearch.pendingCount(), "the search must be registered");

        RtpSearch.cancel(player);

        assertEquals(0, RtpSearch.pendingCount(), "cancel must release the pending entry");
        assertFalse(RtpSearch.isPending(player), "the player must no longer be pending");
    }

    @Test
    void finishClearsPending() {
        UUID player = UUID.randomUUID();

        RtpSearch.markPending(player);
        RtpSearch.finish(player);

        assertFalse(RtpSearch.isPending(player), "finish must release the pending entry");
        assertEquals(0, RtpSearch.pendingCount(), "finish must leave no entry behind");
    }
}
