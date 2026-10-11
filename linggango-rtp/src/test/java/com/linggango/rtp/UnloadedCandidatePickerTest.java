package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Covers picking landing candidates from chunks that are not currently loaded. */
class UnloadedCandidatePickerTest {

    @Test
    void packsChunkLikeChunkPos() {
        // ChunkPos.toLong(): X in the low 32 bits, Z in the high 32 bits.
        assertEquals(1L | (2L << 32), UnloadedCandidatePicker.packChunk(1, 2));
        assertEquals((-3L & 0xFFFFFFFFL) | (5L << 32), UnloadedCandidatePicker.packChunk(-3, 5));
    }

    @Test
    void returnsOnlyUnloadedCandidates() {
        // Mark every chunk the 100..1000 block annulus could reach as loaded.
        Set<Long> loaded = new HashSet<>();
        for (int cx = -70; cx <= 70; cx++) {
            for (int cz = -70; cz <= 70; cz++) {
                loaded.add(UnloadedCandidatePicker.packChunk(cx, cz));
            }
        }
        List<int[]> picked = UnloadedCandidatePicker.pickUnloaded(
                new Random(7), 0, 0, 100, 1000, 0.0, loaded, 40, 200);
        assertTrue(picked.isEmpty(), "all candidates are loaded, so none may be picked");
    }

    @Test
    void everyPickedCandidateIsUnloaded() {
        Set<Long> loaded = new HashSet<>();
        loaded.add(UnloadedCandidatePicker.packChunk(0, 0));
        loaded.add(UnloadedCandidatePicker.packChunk(1, 1));
        List<int[]> picked = UnloadedCandidatePicker.pickUnloaded(
                new Random(11), 0, 0, 100, 1000, 0.0, loaded, 40, 200);
        assertTrue(!picked.isEmpty(), "with almost nothing loaded, candidates should be found");
        for (int[] p : picked) {
            assertTrue(!loaded.contains(UnloadedCandidatePicker.packChunk(p[0] >> 4, p[1] >> 4)),
                    "picked candidate must not be a loaded chunk");
        }
    }

    @Test
    void neverExceedsTheLimit() {
        List<int[]> picked = UnloadedCandidatePicker.pickUnloaded(
                new Random(3), 0, 0, 100, 1000, 0.0, Set.of(), 5, 500);
        assertTrue(picked.size() <= 5, "must not exceed the requested limit");
    }

    @Test
    void triesBoundTheWork() {
        // With zero tries nothing can be produced, even with an empty loaded set.
        List<int[]> picked = UnloadedCandidatePicker.pickUnloaded(
                new Random(5), 0, 0, 100, 1000, 0.0, Set.of(), 40, 0);
        assertTrue(picked.isEmpty());
    }
}
