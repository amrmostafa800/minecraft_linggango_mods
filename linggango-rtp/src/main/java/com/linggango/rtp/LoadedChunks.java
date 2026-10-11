package com.linggango.rtp;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;

/**
 * Reads the set of chunks that are <em>currently loaded in memory</em> for a
 * level, so the {@code /rtp} search can avoid them.
 *
 * <p>Uses {@link ChunkMap#getChunks()} (widened by the mod's access
 * transformer) and keeps every holder whose FULL chunk is present. A chunk that
 * is merely written to a region file but not in memory is deliberately excluded
 * from this set: it is a valid landing target for the unloaded-only search.
 *
 * <p>Must be called on the server thread. The returned positions are a snapshot
 * used only to filter candidates; a chunk that loads afterwards is caught by the
 * per-candidate {@link ServerChunkCache#getChunkNow} re-check.
 */
public final class LoadedChunks {

    private LoadedChunks() {
    }

    /**
     * Collects the packed positions ({@code ChunkPos.toLong()}) of every chunk
     * currently loaded at FULL status in {@code chunkSource}.
     */
    public static java.util.List<Long> positions(ServerChunkCache chunkSource) {
        java.util.List<Long> packed = new java.util.ArrayList<>();
        try {
            ChunkMap map = chunkSource.chunkMap;
            for (ChunkHolder holder : map.getChunks()) {
                if (holder == null || holder.getFullChunk() == null) {
                    continue;
                }
                packed.add(holder.getPos().toLong());
            }
        } catch (Throwable t) {
            // Any change in the chunk internals leaves an empty set, which makes
            // the search consider every candidate unloaded rather than failing.
        }
        return packed;
    }
}
