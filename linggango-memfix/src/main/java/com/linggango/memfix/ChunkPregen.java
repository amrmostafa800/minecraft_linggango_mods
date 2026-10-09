package com.linggango.memfix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Generates the area {@code /rtp} can teleport into, in the background, so the
 * guard in {@link RtpGuard} has somewhere to send players.
 *
 * <p>Refusing ungenerated candidates stops the freeze but on its own would make
 * {@code /rtp} report "could not find a valid location" in unexplored terrain.
 * Generating the annulus once removes both problems: the command then finds a
 * chunk that is already on disk every time, and loading it is off-thread work.
 *
 * <p>Generation is driven from the server tick rather than a thread of its own.
 * Each tick submits a few chunks and collects finished ones, which keeps the
 * chunk pipeline fed while bounding the work per tick, so players see no stall.
 *
 * <p>Chunks are held with this mod's own ticket instead of
 * {@code ServerChunkCache.getChunk}'s. That ticket expires after a single tick,
 * and the vanilla blocking call only finishes because the main thread is
 * stalled waiting on it; an async request would have its chunk dropped before
 * the pipeline finished. The ticket here never expires and is removed as soon
 * as the chunk completes, so chunks still unload normally and memory stays
 * bounded. Nothing is force-loaded and no chunk is kept resident.
 */
final class ChunkPregen {

    private static final String MARKER = "memfix-pregen.txt";

    /**
     * Never expires. Only removed explicitly, when the chunk completes.
     */
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create("memfix_pregen", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Passed to addRegionTicket, which subtracts it from the level needed for
     * FULL. Zero asks for exactly FULL with no extra margin.
     *
     * <p>Do not pass a chunk level here. addRegionTicket treats the number as a
     * distance, so a level of 33 would resolve to ticket level 0, which
     * force-loads the chunk and propagates a load demand roughly 33 chunks in
     * every direction -- thousands of chunks per ticket.
     */
    private static final int TICKET_DISTANCE = 0;

    private static final Deque<ChunkPos> PENDING = new ArrayDeque<>();
    private static final List<InFlight> IN_FLIGHT = new ArrayList<>();

    private static ServerLevel level;
    private static long total;
    private static long completed;
    private static long skipped;
    private static boolean active;
    private static long lastLogTick;

    private ChunkPregen() {
    }

    private record InFlight(ChunkPos pos, CompletableFuture<?> future) {
    }

    /** Starts pregeneration if it is enabled and this area is not already done. */
    static void start(MinecraftServer server) {
        if (!MemFixConfig.pregenEnabled()) {
            return;
        }
        if (MemFixConfig.pregenRadiusBlocks() <= 0) {
            return;
        }

        ResourceLocation id = ResourceLocation.tryParse(MemFixConfig.pregenDimension());
        if (id == null) {
            MemFix.LOGGER.warn("[memfix] pregen: bad dimension '{}'", MemFixConfig.pregenDimension());
            return;
        }
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id);
        ServerLevel target = server.getLevel(key);
        if (target == null) {
            MemFix.LOGGER.warn("[memfix] pregen: dimension '{}' is not loaded", id);
            return;
        }

        String fingerprint = fingerprint();
        if (fingerprint.equals(readMarker(server))) {
            MemFix.LOGGER.info("[memfix] pregen: {} already generated, skipping", describe());
            return;
        }

        PENDING.clear();
        IN_FLIGHT.clear();
        skipped = 0;
        completed = 0;
        level = target;

        // A candidate may sit up to half a chunk from its chunk's centre, so the
        // square has to reach a little past the requested radius.
        int chunkRadius = (int) Math.ceil((MemFixConfig.pregenRadiusBlocks() + 16.0) / 16.0);
        int cx = MemFixConfig.pregenCenterX();
        int cz = MemFixConfig.pregenCenterZ();
        for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
            for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) {
                if ((long) (x - cx) * (x - cx) + (long) (z - cz) * (z - cz)
                        <= (long) chunkRadius * chunkRadius) {
                    PENDING.add(new ChunkPos(x, z));
                }
            }
        }

        total = PENDING.size();
        active = true;
        MemFix.LOGGER.info("[memfix] pregen: generating {} in {} around ({}, {}) at {} chunks/tick",
                describe(), id, cx, cz, MemFixConfig.pregenChunksPerTick());
    }

    /** One tick's worth of work: retire finished chunks, then submit more. */
    static void tick(MinecraftServer server) {
        RegionIndex.invalidate();
        if (!active) {
            return;
        }

        for (int i = IN_FLIGHT.size() - 1; i >= 0; i--) {
            InFlight in = IN_FLIGHT.get(i);
            if (!in.future().isDone()) {
                continue;
            }
            IN_FLIGHT.remove(i);
            release(in.pos());
            completed++;
        }

        int budget = MemFixConfig.pregenChunksPerTick();
        int maxInFlight = MemFixConfig.pregenMaxInFlight();
        ServerChunkCache cache = level.getChunkSource();
        while (budget-- > 0 && IN_FLIGHT.size() < maxInFlight && !PENDING.isEmpty()) {
            ChunkPos pos = PENDING.poll();
            if (RegionIndex.chunkExists(regionDir(), pos.x, pos.z)) {
                // Already generated by an earlier run; no ticket, no load.
                skipped++;
                completed++;
                continue;
            }
            cache.addRegionTicket(TICKET, pos, TICKET_DISTANCE, pos);
            IN_FLIGHT.add(new InFlight(pos, cache.getChunkFuture(pos.x, pos.z, ChunkStatus.FULL, true)));
        }

        if (completed >= total) {
            finish(server);
            return;
        }

        long now = server.getTickCount();
        if (now - lastLogTick >= 600L) {
            lastLogTick = now;
            MemFix.LOGGER.info("[memfix] pregen: {}/{} chunks ({} already present, {} in flight)",
                    completed, total, skipped, IN_FLIGHT.size());
        }
    }

    private static void release(ChunkPos pos) {
        try {
            level.getChunkSource().removeRegionTicket(TICKET, pos, TICKET_DISTANCE, pos);
        } catch (Throwable t) {
            MemFix.LOGGER.warn("[memfix] pregen: could not release ticket at {}: {}", pos, t.toString());
        }
    }

    private static void finish(MinecraftServer server) {
        active = false;
        PENDING.clear();
        IN_FLIGHT.clear();
        writeMarker(server);
        MemFix.LOGGER.info("[memfix] pregen: finished {} ({} were already present)", describe(), skipped);
        level = null;
    }

    private static String describe() {
        return MemFixConfig.pregenRadiusBlocks() + "-block area";
    }

    private static String fingerprint() {
        return String.format(Locale.ROOT, "%s|%d|%d|%d",
                MemFixConfig.pregenDimension(),
                MemFixConfig.pregenCenterX(),
                MemFixConfig.pregenCenterZ(),
                MemFixConfig.pregenRadiusBlocks());
    }

    private static Path regionDir() {
        return RegionIndex.regionDir(level);
    }

    private static Path markerPath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
    }

    private static String readMarker(MinecraftServer server) {
        try {
            Path p = markerPath(server);
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeMarker(MinecraftServer server) {
        try {
            Files.writeString(markerPath(server), fingerprint() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            MemFix.LOGGER.warn("[memfix] pregen: could not write marker: {}", e.toString());
        }
    }

    /** Releases outstanding tickets so a stop mid-generation does not hold chunks. */
    static void stop() {
        if (!active || level == null) {
            return;
        }
        for (InFlight in : IN_FLIGHT) {
            release(in.pos());
        }
        IN_FLIGHT.clear();
        PENDING.clear();
        active = false;
        level = null;
    }
}
