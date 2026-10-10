package com.linggango.rtp;

import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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
 * Runs the asynchronous {@code /rtp} search.
 *
 * <p>Each attempt picks a uniformly-by-area candidate, then loads its chunk with
 * {@link ServerChunkCache#getChunkFuture}. The chunk load is the only work that
 * leaves the tick; the returned future completes on the server thread during
 * chunk ticking, so every read of world state (biome, heightmap, FTB Chunks
 * claim) and the teleport itself run in the future's callback, never off-thread.
 * Nothing calls {@code join}/{@code get} on the future.
 *
 * <p>A search is single-flight per player: {@link #start} refuses while one is
 * already pending. The pending entry is removed on every exit path (success,
 * exhaustion, {@link #cancel}, and lifecycle cancellation), so the registry
 * cannot leak.
 */
public final class RtpSearch {

    private static final Map<UUID, Search> PENDING = new ConcurrentHashMap<>();

    private RtpSearch() {
    }

    /** Begins a search for {@code player}, unless one is already pending. */
    public static void start(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (PENDING.containsKey(playerId)) {
            message(player, "already searching");
            return;
        }

        ServerLevel level;
        try {
            level = player.serverLevel();
        } catch (Throwable t) {
            return;
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            // /rtp is overworld-only; the command hook already enforces this, so
            // this is a defensive guard rather than a user-facing path.
            return;
        }

        Search search = new Search(playerId, player, level);
        PENDING.put(playerId, search);
        attempt(search, 1);
    }

    /** Drops the pending search for {@code playerId}, if any. */
    public static void cancel(UUID playerId) {
        PENDING.remove(playerId);
    }

    /** Drops every pending search. */
    public static void cancelAll() {
        PENDING.clear();
    }

    /** Number of in-flight searches; used by the lifecycle tests. */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** Test seam: registers a pending search without touching the server. */
    static void markPending(UUID playerId) {
        PENDING.put(playerId, new Search(playerId, null, null));
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
     * One attempt: pick a candidate, ask for its chunk, and validate in the
     * callback. Runs on the server thread.
     */
    private static void attempt(Search search, int attemptNumber) {
        if (!isCurrent(search)) {
            return;
        }
        if (playerGone(search.player, search.level)) {
            finish(search.playerId);
            return;
        }

        int attempts = Math.max(1, RtpConfig.findAttempts());
        if (attemptNumber > attempts) {
            message(search.player, "Could not find a valid location");
            finish(search.playerId);
            return;
        }

        int maxRadius = RtpConfig.maxRadius();
        int[] candidate = CandidatePicker.pick(search.rng,
                RtpConfig.centerX(), RtpConfig.centerZ(),
                RtpConfig.minRadius(), maxRadius,
                borderHalfSize(search.level, maxRadius));
        int x = candidate[0];
        int z = candidate[1];

        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
                search.level.getChunkSource().getChunkFuture(x >> 4, z >> 4, ChunkStatus.FULL, true);
        future.thenAccept(either -> onChunkReady(search, attemptNumber, x, z, either));
    }

    /**
     * Validates a loaded candidate on the server thread and either teleports or
     * retries. The {@code Either} may carry a loading failure, in which case the
     * candidate is simply retried.
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

        ChunkAccess chunk = either.left().orElse(null);
        if (chunk != null) {
            BlockPos column = new BlockPos(x, 0, z);
            if (!LandingValidator.biomeExcluded(biomeIdAt(search.level, column))) {
                OptionalInt safeY = LandingValidator.findSafeY(search.level, column);
                if (safeY.isPresent()) {
                    BlockPos landing = new BlockPos(x, safeY.getAsInt(), z);
                    if (!claimsBlock(search, landing)) {
                        if (FtbAccess.teleportTo(search.player, search.level.dimension(), landing)) {
                            message(search.player, "Found a location after " + attemptNumber + " attempts");
                            finish(search.playerId);
                            return;
                        }
                    }
                }
            }
        }

        attempt(search, attemptNumber + 1);
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

    private static final class Search {
        final UUID playerId;
        final ServerPlayer player;
        final ServerLevel level;
        final Random rng;

        Search(UUID playerId, ServerPlayer player, ServerLevel level) {
            this.playerId = playerId;
            this.player = player;
            this.level = level;
            this.rng = new Random();
        }
    }
}
