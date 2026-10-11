package com.linggango.rtp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers "has this chunk already been written to disk?" from an Anvil region
 * file header, without loading or generating the chunk.
 *
 * <p>The vanilla chunk API cannot answer this cheaply. A chunk that exists on
 * disk but is not in memory has no {@code ChunkHolder}, so the only way to ask
 * through {@code ServerChunkCache} is to request it, which loads <em>or
 * generates</em> it — exactly the work the search has to avoid. Reading the
 * region header instead never touches the chunk pipeline, so a candidate in
 * never-generated terrain is rejected in microseconds.
 *
 * <p>A region file starts with a 1024-entry table of 4-byte records (a 3-byte
 * sector offset plus a 1-byte sector count) covering the 32x32 chunks the file
 * holds. A zero offset means the chunk was never written. Only those 8 KiB are
 * read, and headers are cached because one region file spans 512x512 blocks, so
 * a single {@code /rtp} usually probes several chunks inside the same file.
 *
 * <p>The cache is short-lived on purpose: generation writes new chunks into
 * region files asynchronously, so a longer-lived cache would report stale
 * absences. {@link #invalidate()} is called from the server tick.
 */
final class ChunkIndex {

    private static final int HEADER_BYTES = 8192;
    private static final int MAX_CACHED_FILES = 256;

    /** Region-file headers, keyed by directory and region coordinate, LRU-ordered. */
    private static final Map<RegionKey, byte[]> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<RegionKey, byte[]> eldest) {
            return size() > MAX_CACHED_FILES;
        }
    };

    /**
     * Cache key: the region directory is part of the key because region
     * coordinates repeat across dimensions (every dimension has an
     * {@code r.0.0.mca}); without it one dimension's header would answer for
     * another's.
     */
    private record RegionKey(Path dir, int regionX, int regionZ) {
    }

    private ChunkIndex() {
    }

    /** Drops cached headers so the next probe re-reads them from disk. */
    static synchronized void invalidate() {
        CACHE.clear();
    }

    /**
     * True when the chunk has been written to this region directory at some
     * point. A false result is always safe: it only means the caller declines to
     * touch the chunk. A true result can still be stale within one tick.
     */
    static boolean chunkExists(Path regionDir, int chunkX, int chunkZ) {
        byte[] header = header(regionDir, chunkX >> 5, chunkZ >> 5);
        if (header == null) {
            return false;
        }
        int entry = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
        int offset = ((header[entry] & 0xFF) << 16)
                | ((header[entry + 1] & 0xFF) << 8)
                | (header[entry + 2] & 0xFF);
        return offset != 0;
    }

    /**
     * True when the chunk and every chunk within {@code margin} of it (a
     * {@code (2*margin+1)^2} square) are already on disk.
     *
     * <p>Loading a candidate at FULL status also loads its neighbours, because
     * the full-status generation step runs the structure/feature pipeline over
     * the surrounding chunks. Requiring the whole neighbourhood on disk is what
     * guarantees a candidate can be loaded without generating anything.
     */
    static boolean neighborhoodOnDisk(Path regionDir, int chunkX, int chunkZ, int margin) {
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dz = -margin; dz <= margin; dz++) {
                if (!chunkExists(regionDir, chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The directory holding a dimension's region files. The Overworld, Nether
     * and End sit directly under the world root; modded dimensions use the
     * {@code dimensions/<namespace>/<path>} tree.
     */
    static Path regionDir(net.minecraft.server.level.ServerLevel level) {
        Path root = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        net.minecraft.resources.ResourceLocation id = level.dimension().location();
        String folder = switch (id.toString()) {
            case "minecraft:overworld" -> "";
            case "minecraft:the_nether" -> "DIM-1";
            case "minecraft:the_end" -> "DIM1";
            default -> "dimensions/" + id.getNamespace() + "/" + id.getPath();
        };
        return folder.isEmpty() ? root.resolve("region") : root.resolve(folder).resolve("region");
    }

    private static synchronized byte[] header(Path regionDir, int regionX, int regionZ) {
        RegionKey key = new RegionKey(regionDir, regionX, regionZ);
        byte[] cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Path file = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Fill the header; a short read means a truncated file.
            }
        } catch (IOException e) {
            return null;
        }
        if (buffer.position() < HEADER_BYTES) {
            return null;
        }

        byte[] header = buffer.array();
        CACHE.put(key, header);
        return header;
    }
}
