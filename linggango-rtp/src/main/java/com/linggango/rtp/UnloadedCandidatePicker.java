package com.linggango.rtp;

import java.util.List;
import java.util.Random;

/**
 * Picks landing candidates for the {@code /rtp} search, preferring chunks that
 * are <em>not currently loaded in memory</em>.
 *
 * <p>The point of RTP is to move a player somewhere new. Landing in an already
 * loaded chunk just moves them within the area they can already see. So the
 * picker draws candidates from the random annulus and drops the ones that are
 * loaded, keeping the unloaded ones — which are then generated when the player
 * arrives. When the on-disk gate is enabled
 * ({@link ChunkIndex#neighborhoodOnDisk}) the search further restricts itself to
 * unloaded terrain that already exists on disk, so nothing is generated.
 *
 * <p>Pure and deterministic given the supplied {@link Random}, so it is
 * unit-testable without any Minecraft classes.
 */
public final class UnloadedCandidatePicker {

    private UnloadedCandidatePicker() {
    }

    /**
     * Draws up to {@code tries} candidates uniformly by area in the annulus
     * around {@code (centerX, centerZ)} and returns the first {@code limit} that
     * are not in {@code loadedPacked}.
     *
     * @param rng          source of randomness
     * @param centerX      annulus centre X
     * @param centerZ      annulus centre Z
     * @param minRadius    inner radius, in blocks
     * @param maxRadius    outer radius, in blocks
     * @param borderHalfSize finite world-border half size, or {@code <= 0} for none
     * @param loadedPacked packed chunk positions currently loaded (to avoid)
     * @param limit        maximum number of candidates to return
     * @param tries        number of draws to attempt before giving up
     * @return a fresh mutable list of {@code int[]{x, z}} block coordinates; may
     *         be shorter than {@code limit}, or empty, when too few unloaded
     *         candidates were drawn
     */
    public static List<int[]> pickUnloaded(Random rng, int centerX, int centerZ,
                                           int minRadius, int maxRadius, double borderHalfSize,
                                           java.util.Collection<Long> loadedPacked, int limit, int tries) {
        java.util.Set<Long> loaded = loadedPacked instanceof java.util.Set
                ? (java.util.Set<Long>) loadedPacked
                : new java.util.HashSet<>(loadedPacked);

        List<int[]> picked = new java.util.ArrayList<>();
        for (int i = 0; i < tries && picked.size() < limit; i++) {
            int[] candidate = CandidatePicker.pick(rng, centerX, centerZ, minRadius, maxRadius, borderHalfSize);
            long packed = packChunk(candidate[0] >> 4, candidate[1] >> 4);
            if (!loaded.contains(packed)) {
                picked.add(candidate);
            }
        }
        return picked;
    }

    /** Packs like {@code ChunkPos.toLong()}: chunk X in the low 32 bits, Z in the high. */
    static long packChunk(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((long) chunkZ << 32);
    }
}
