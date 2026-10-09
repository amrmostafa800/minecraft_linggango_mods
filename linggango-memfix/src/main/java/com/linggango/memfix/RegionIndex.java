package com.linggango.memfix;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Answers "has this chunk ever been written to disk?" from the Anvil region
 * file header, without loading or generating the chunk.
 *
 * <p>The vanilla chunk API cannot answer this cheaply. A chunk that exists on
 * disk but is not in memory has no {@code ChunkHolder}, so the only way to ask
 * through {@code ServerChunkCache} is to request it, which loads or generates
 * it - exactly the work {@code /rtp} has to avoid doing on the server thread.
 *
 * <p>A region file starts with a 1024-entry table of 4-byte records (a 3-byte
 * sector offset plus a 1-byte sector count) covering the 32x32 chunks the file
 * holds. A zero offset means the chunk was never written. Only those 8 KiB are
 * read, and headers are cached because one region file spans 512x512 blocks, so
 * a single {@code /rtp} usually probes several chunks inside the same file.
 *
 * <p>The cache is invalidated every server tick. Generation writes new chunks
 * into region files asynchronously, so a longer-lived cache would report stale
 * absences; a one-tick lifetime still collapses the many probes of one command
 * into a handful of reads.
 */
final class RegionIndex {

    private static final int HEADER_BYTES = 8192;
    private static final int MAX_CACHED_FILES = 64;

    /** Region-file headers, keyed by packed region coordinate. */
    private static final Map<Long, byte[]> CACHE = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
            return size() > MAX_CACHED_FILES;
        }
    };

    private RegionIndex() {
    }

    /** Drops cached headers so the next probe re-reads them from disk. */
    static synchronized void invalidate() {
        CACHE.clear();
    }

    /**
     * True when the chunk has been written to this region directory at some
     * point. A false result is always safe: it only means the caller declines
     * to touch the chunk. A true result can still be stale within one tick.
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
     * The directory holding a dimension's region files. The Overworld, Nether
     * and End sit directly under the world root; modded dimensions use the
     * {@code dimensions/<namespace>/<path>} tree.
     */
    static Path regionDir(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        ResourceLocation id = level.dimension().location();
        String folder = switch (id.toString()) {
            case "minecraft:overworld" -> "";
            case "minecraft:the_nether" -> "DIM-1";
            case "minecraft:the_end" -> "DIM1";
            default -> "dimensions/" + id.getNamespace() + "/" + id.getPath();
        };
        return folder.isEmpty() ? root.resolve("region") : root.resolve(folder).resolve("region");
    }

    private static synchronized byte[] header(Path regionDir, int regionX, int regionZ) {
        long key = ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
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
