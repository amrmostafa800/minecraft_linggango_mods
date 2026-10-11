package com.linggango.rtp;

import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.datafixers.util.Either;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

/**
 * Runs the asynchronous {@code /rtp} search off the server tick thread.
 *
 * <p>The candidate loop runs on a single daemon worker thread this class owns.
 * Candidate picking and the on-disk gate are pure and run on the worker; the
 * chunk request and every read of live world state (biome, heightmap, FTB
 * Chunks claim) plus the teleport itself run on the server thread.
 *
 * <p>The chunk request MUST run on the server thread. In 1.20.1
 * {@link ServerChunkCache#getChunkFuture} only force-loads a chunk when called
 * on the server thread, where it adds the ticket, runs the distance-manager
 * update and pumps the chunk system via {@code managedBlock} until the chunk is
 * FULL. Called off the server thread it merely enqueues that work and returns
 * the holder's <em>current</em> future; for a chunk that is not already loaded
 * that future is the shared, already completed {@code ChunkHolder.UNLOADED}
 * sentinel, so awaiting it off-thread reports a load failure before the load
 * even begins. The worker therefore hands each candidate to the server thread
 * ({@code server.execute(...)}) and never requests a chunk itself.
 *
 * <p>A search is single-flight per player: {@link #start} refuses while one is
 * already pending. The pending entry is removed on every exit path (success,
 * exhaustion, an exceptional chunk load, {@link #cancel}, and lifecycle
 * cancellation), so the registry cannot leak.
 */
public final class RtpSearch {

    private static final Map<UUID, Search> PENDING = new ConcurrentHashMap<>();
    /** Sentinel value marking a claimed-but-not-yet-built search. */
    private static final Search CLAIM = new Search(null, null, null, 0.0, 0, 0, null, 0, false);

    /** Single daemon worker; the candidate loop and chunk loads run here. */
    private static final Object WORKER_LOCK = new Object();
    private static volatile ExecutorService worker = newWorker();

    private RtpSearch() {
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "linggango-rtp-search");
            t.setDaemon(true);
            return t;
        });
    }

    /** Returns the worker, recreating it if a previous server stop shut it down. */
    private static ExecutorService ensureWorker() {
        ExecutorService executor = worker;
        if (executor != null) {
            return executor;
        }
        synchronized (WORKER_LOCK) {
            if (worker == null) {
                worker = newWorker();
            }
            return worker;
        }
    }

    /** Begins a search for {@code player}, unless one is already pending. */
    public static void start(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!tryBegin(playerId)) {
            message(player, "already searching");
            return;
        }

        ServerLevel level;
        int minRadius;
        int maxRadius;
        double borderHalfSize;
        Path regionDir;
        boolean existingChunksOnly;
        boolean unloadedChunksOnly;
        int chunkMargin;
        try {
            level = player.serverLevel();
        } catch (Throwable t) {
            finish(playerId);
            return;
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            // /rtp is overworld-only; the command hook already enforces this, so
            // this is a defensive guard rather than a user-facing path.
            finish(playerId);
            return;
        }
        try {
            // Capture the immutable search geometry HERE, on the server thread,
            // before the worker is scheduled: the worker must never read world
            // or border state itself.
            maxRadius = RtpConfig.maxRadius();
            minRadius = RtpConfig.minRadius();
            borderHalfSize = borderHalfSize(level, maxRadius);
            unloadedChunksOnly = RtpConfig.unloadedChunksOnly();
            existingChunksOnly = RtpConfig.existingChunksOnly();
            chunkMargin = RtpConfig.chunkMargin();
            regionDir = existingChunksOnly ? ChunkIndex.regionDir(level) : null;
        } catch (Throwable t) {
            finish(playerId);
            return;
        }

        Search search = new Search(playerId, player, level, borderHalfSize, minRadius, maxRadius,
                regionDir, chunkMargin, unloadedChunksOnly);
        if (unloadedChunksOnly) {
            // Snapshot the loaded set once, on the server thread; the worker uses
            // it to reject candidates that are already loaded. A chunk that loads
            // later is caught by the per-candidate getChunkNow re-check.
            search.loadedPacked = loadedChunkPositions(level);
        }
        PENDING.put(playerId, search);
        debug("start player=%s center=(%d,%d) radius=%d..%d attempts=%d unloadedOnly=%s diskGate=%s margin=%d loaded=%d",
                playerId, RtpConfig.centerX(), RtpConfig.centerZ(), minRadius, maxRadius,
                RtpConfig.findAttempts(), unloadedChunksOnly, existingChunksOnly, chunkMargin,
                search.loadedPacked.size());
        schedule(search, 1);
    }

    /** Packs the position of every chunk currently loaded at FULL status. */
    private static java.util.List<Long> loadedChunkPositions(ServerLevel level) {
        try {
            return LoadedChunks.positions(level.getChunkSource());
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /**
     * Enqueues the next attempt onto the worker thread. Never runs on the tick
     * thread. This method itself never throws: a task that fails before reaching
     * its own guards must still release the pending entry, or the search would
     * leak.
     */
    private static void schedule(Search search, int attemptNumber) {
        try {
            ensureWorker().execute(() -> {
                try {
                    attempt(search, attemptNumber);
                } catch (Throwable t) {
                    finish(search.playerId);
                }
            });
        } catch (Throwable t) {
            // The executor is gone (server stopping): drop the pending entry.
            finish(search.playerId);
        }
    }

    /**
     * Atomically claims the single-flight slot for {@code playerId}. Returns
     * {@code false} (without registering anything) when a search is already
     * pending, else registers a placeholder claim and returns {@code true}.
     * {@link #start} replaces the placeholder with the real search; every exit
     * path clears the entry via {@link #finish}.
     */
    static boolean tryBegin(UUID playerId) {
        return PENDING.putIfAbsent(playerId, CLAIM) == null;
    }

    /** Drops the pending search for {@code playerId}, if any. */
    public static void cancel(UUID playerId) {
        PENDING.remove(playerId);
    }

    /** Drops every pending search and shuts the worker down (server stop). */
    public static void cancelAll() {
        PENDING.clear();
        ExecutorService executor;
        synchronized (WORKER_LOCK) {
            executor = worker;
            worker = null;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Number of in-flight searches; used by the lifecycle tests. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** Test seam: registers a pending search without touching the server. */
    static void markPending(UUID playerId) {
        tryBegin(playerId);
    }

    /** Test seam: whether a search is pending for {@code playerId}. */
    static boolean isPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    /** Test seam: releases the pending entry for {@code playerId}. */
    static void finish(UUID playerId) {
        PENDING.remove(playerId);
    }

    /**
     * One attempt: runs on the WORKER thread. It checks the captured state,
     * picks a candidate from the captured geometry, and schedules the chunk load
     * with {@link ServerChunkCache#getChunkFuture}. Because this is off the
     * server thread the call returns immediately (it does not {@code managedBlock}
     * the tick). The completion callback marshals validation back onto the
     * server thread.
     */
    private static void attempt(Search search, int attemptNumber) {
        if (!isCurrent(search)) {
            return;
        }
        // No live world/player state is read here: the worker only touches the
        // captured geometry. The player-gone guard runs on the server thread in
        // onChunkReady, where the read is safe.

        int attempts = Math.max(1, RtpConfig.findAttempts());
        if (attemptNumber > attempts) {
            debug("exhausted after %d attempts (no valid landing found)", attempts);
            serverExecute(search, () -> message(search.player, "Could not find a valid location"),
                    search.playerId);
            finish(search.playerId);
            return;
        }

        int[] candidate = CandidatePicker.pick(search.rng,
                RtpConfig.centerX(), RtpConfig.centerZ(),
                search.minRadius, search.maxRadius,
                search.borderHalfSize);
        int x = candidate[0];
        int z = candidate[1];

        // RTP must land the player in fresh terrain, so reject candidates whose
        // chunk is already loaded. The snapshot was taken on the server thread at
        // start; requestChunk re-checks on the server thread in case one loaded
        // since.
        if (search.unloadedChunksOnly
                && search.loadedPacked.contains(UnloadedCandidatePicker.packChunk(x >> 4, z >> 4))) {
            debug("attempt %d: (%d,%d) rejected, chunk already loaded", attemptNumber, x, z);
            schedule(search, attemptNumber + 1);
            return;
        }

        // Reject terrain that is not already on disk before asking the chunk
        // system for it. Requesting a chunk at FULL status loads it if it
        // exists, but GENERATES it if it does not - the exact work that stalls
        // the server tick. Reading the region header answers this without
        // touching the chunk pipeline, so a candidate in never-generated
        // terrain costs microseconds and is retried immediately.
        if (search.regionDir != null
                && !ChunkIndex.neighborhoodOnDisk(search.regionDir, x >> 4, z >> 4, search.chunkMargin)) {
            debug("attempt %d: (%d,%d) rejected, chunk not on disk", attemptNumber, x, z);
            schedule(search, attemptNumber + 1);
            return;
        }

        debug("attempt %d: candidate (%d,%d), requesting chunk (%d,%d)",
                attemptNumber, x, z, x >> 4, z >> 4);
        // The chunk request must run on the server thread. In 1.20.1
        // ServerChunkCache#getChunkFuture only force-loads the chunk (it adds the
        // ticket, runs the distance-manager update and pumps the chunk system
        // through managedBlock) when it is called ON the server thread; off it,
        // the call merely enqueues that work and returns the holder's current
        // future, which for a not-yet-loaded chunk is the shared, already
        // completed ChunkHolder.UNLOADED sentinel. Awaiting that off-thread
        // future therefore reports "chunk load failed" before the load ever
        // starts. So the worker only hands the request to the server thread.
        serverExecute(search, () -> requestChunk(search, attemptNumber, x, z), search.playerId);
    }

    /**
     * Requests the candidate chunk on the server thread and, once it is
     * available, validates it through {@link #onChunkReady}. Called only from
     * the tick thread. On this thread {@code getChunkFuture} force-loads the
     * chunk and returns a future that is already complete (it pumps the chunk
     * system via {@code managedBlock} until the chunk reaches FULL), so the
     * common path validates inline; the callback is a fallback for the case
     * where the future is still pending, and never blocks the tick.
     */
    private static void requestChunk(Search search, int attemptNumber, int x, int z) {
        if (!isCurrent(search)) {
            return;
        }
        if (playerGone(search.player, search.level)) {
            finish(search.playerId);
            return;
        }
        // Re-check on the server thread: a chunk that was unloaded at start may
        // have loaded since (a player walked near it), which would make it a
        // non-fresh landing target.
        if (search.unloadedChunksOnly && search.level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
            debug("attempt %d: (%d,%d) rejected, chunk loaded since start", attemptNumber, x, z);
            schedule(search, attemptNumber + 1);
            return;
        }

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future;
        try {
            future = search.level.getChunkSource().getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true);
        } catch (Throwable t) {
            onChunkReady(search, attemptNumber, x, z, null);
            return;
        }

        Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either = future.getNow(null);
        if (either != null) {
            onChunkReady(search, attemptNumber, x, z, either);
            return;
        }
        // Defensive: the server-thread call blocks until the chunk is ready, so
        // this should be unreachable. If the future is somehow still pending,
        // resume from the callback rather than blocking the tick.
        future.whenComplete((done, err) -> serverExecute(search,
                () -> onChunkReady(search, attemptNumber, x, z, err != null ? null : done),
                search.playerId));
    }

    /**
     * Runs {@code task} on the server thread, or releases the pending entry if
     * the server is unavailable or the task cannot be enqueued. The worker never
     * waits: this only enqueues. This method never throws.
     */
    private static void serverExecute(Search search, Runnable task, UUID playerId) {
        try {
            search.level.getServer().execute(task);
        } catch (Throwable t) {
            finish(playerId);
        }
    }

    /**
     * Validates a loaded candidate on the server thread and either teleports or
     * retries. A {@code null} {@code either}, or one carrying a loading failure,
     * means the chunk is unavailable and the candidate is simply retried. A
     * retry is re-scheduled onto the worker, not recursed here, so the server
     * thread only ever enqueues the next request.
     */
    private static void onChunkReady(Search search, int attemptNumber, int x, int z,
                                     Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure> either) {
        if (!isCurrent(search)) {
            return;
        }
        if (playerGone(search.player, search.level)) {
            finish(search.playerId);
            return;
        }

        ChunkAccess chunk = either == null ? null : either.left().orElse(null);
        if (chunk != null) {
            BlockPos column = new BlockPos(x, 0, z);
            ResourceLocation biomeId = biomeIdAt(search.level, column);
            if (!LandingValidator.biomeExcluded(biomeId)
                    && !LandingValidator.biomeTagIgnored(search.level, column)) {
                OptionalInt safeY = LandingValidator.findSafeY(search.level, column);
                if (safeY.isPresent()) {
                    BlockPos landing = new BlockPos(x, safeY.getAsInt(), z);
                    if (!claimsBlock(search, landing)) {
                        if (FtbAccess.teleportTo(search.player, search.level.dimension(), landing)) {
                            debug("attempt %d: landed at (%d,%d,%d)", attemptNumber, x, landing.getY(), z);
                            message(search.player, "Found a location after " + attemptNumber + " attempts");
                            finish(search.playerId);
                            return;
                        }
                        debug("attempt %d: (%d,%d) FTB refused the teleport", attemptNumber, x, z);
                    } else {
                        debug("attempt %d: (%d,%d) claimed by another team", attemptNumber, x, z);
                    }
                } else {
                    debug("attempt %d: (%d,%d) no safe landing column", attemptNumber, x, z);
                }
            } else {
                debug("attempt %d: (%d,%d) biome excluded (%s)", attemptNumber, x, z, biomeId);
            }
        } else {
            debug("attempt %d: (%d,%d) chunk load failed", attemptNumber, x, z);
        }

        schedule(search, attemptNumber + 1);
    }

    private static boolean claimsBlock(Search search, BlockPos landing) {
        return RtpConfig.respectClaims()
                && LandingValidator.claimedByOtherTeam(search.player, search.level, landing);
    }

    /**
     * The overworld border half-size to clamp candidates to, or {@code 0} when
     * the border is effectively infinite. The vanilla default border is
     * 29,999,984 blocks wide, far larger than {@code maxRadius}; passing its
     * half-size would be pointless, so only a border <em>smaller</em> than
     * {@code maxRadius} is reported (and a non-positive result means unclamped).
     */
    private static double borderHalfSize(ServerLevel level, int maxRadius) {
        try {
            double half = level.getWorldBorder().getSize() / 2.0;
            return half < maxRadius ? half : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static ResourceLocation biomeIdAt(ServerLevel level, BlockPos column) {
        try {
            return level.getBiome(column).unwrapKey()
                    .map(ResourceKey::location)
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the search is still the one registered for the player. */
    private static boolean isCurrent(Search search) {
        return PENDING.get(search.playerId) == search;
    }

    /** True when the player has left, died out of the level, or changed level. */
    private static boolean playerGone(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null) {
            return true;
        }
        try {
            if (player.isRemoved() || player.hasDisconnected()) {
                return true;
            }
            return !player.serverLevel().dimension().equals(level.dimension());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void message(ServerPlayer player, String text) {
        try {
            player.sendSystemMessage(Component.literal(text));
        } catch (Throwable t) {
            // A player who cannot receive a message is about to leave anyway.
        }
    }

    /**
     * Logs a search decision at INFO when {@code debug} is on. The search runs
     * off the tick thread and the player only ever sees the outcome, so without
     * this a failed or slow search is invisible in the server log.
     */
    private static void debug(String format, Object... args) {
        try {
            if (RtpConfig.debug()) {
                LinggangoRtp.LOGGER.info("[rtp] " + String.format(format, args));
            }
        } catch (Throwable t) {
            // Logging must never break the search.
        }
    }

    private static final class Search {
        final UUID playerId;
        final ServerPlayer player;
        final ServerLevel level;
        final Random rng;
        /** Search geometry captured on the server thread; never re-read off-thread. */
        final double borderHalfSize;
        final int minRadius;
        final int maxRadius;
        /** Region directory to gate candidates on, or null when the gate is off. */
        final Path regionDir;
        final int chunkMargin;
        /** True when candidates must NOT be currently loaded in memory. */
        final boolean unloadedChunksOnly;
        /** Packed chunk positions that were loaded at start; empty unless unloaded-only. */
        java.util.List<Long> loadedPacked = java.util.List.of();

        Search(UUID playerId, ServerPlayer player, ServerLevel level,
               double borderHalfSize, int minRadius, int maxRadius,
               Path regionDir, int chunkMargin, boolean unloadedChunksOnly) {
            this.playerId = playerId;
            this.player = player;
            this.level = level;
            this.rng = new Random();
            this.borderHalfSize = borderHalfSize;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
            this.regionDir = regionDir;
            this.chunkMargin = chunkMargin;
            this.unloadedChunksOnly = unloadedChunksOnly;
        }
    }
}
