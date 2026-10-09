package com.linggango.memfix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the region-header reader against real files. This is the part that
 * decides whether {@code /rtp} may use a candidate, so an off-by-one in the
 * table index or a wrong endianness would either send players into ungenerated
 * terrain (bringing the freeze back) or reject valid spots.
 */
class RegionIndexTest {

    private static final int HEADER_BYTES = 8192;

    @TempDir
    Path dir;

    /** Writes a region file whose header marks the given chunks as present. */
    private void writeRegion(int regionX, int regionZ, int... chunkIndices) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        for (int index : chunkIndices) {
            // 3-byte big-endian sector offset, then a 1-byte sector count.
            header.put(index * 4, (byte) 0x00);
            header.put(index * 4 + 1, (byte) 0x00);
            header.put(index * 4 + 2, (byte) 0x02);
            header.put(index * 4 + 3, (byte) 0x01);
        }
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            channel.write(header);
        }
        RegionIndex.invalidate();
    }

    private static int index(int chunkX, int chunkZ) {
        return (chunkX & 31) + (chunkZ & 31) * 32;
    }

    @Test
    void absentFileMeansNoChunk() {
        assertFalse(RegionIndex.chunkExists(dir, 0, 0));
    }

    @Test
    void zeroOffsetMeansChunkWasNeverWritten() throws IOException {
        writeRegion(0, 0, index(0, 0));
        assertTrue(RegionIndex.chunkExists(dir, 0, 0));
        assertFalse(RegionIndex.chunkExists(dir, 1, 0));
        assertFalse(RegionIndex.chunkExists(dir, 0, 1));
    }

    @Test
    void lastSlotOfTheTableIsAddressable() throws IOException {
        // Chunk (31,31) is entry 1023, the final 4-byte record in the header.
        writeRegion(0, 0, index(31, 31));
        assertTrue(RegionIndex.chunkExists(dir, 31, 31));
        assertFalse(RegionIndex.chunkExists(dir, 31, 30));
    }

    @Test
    void negativeChunksUseTheCorrectRegionAndSlot() throws IOException {
        // Chunk -1,-1 lives in region -1,-1 at local slot 31,31.
        writeRegion(-1, -1, index(31, 31));
        assertTrue(RegionIndex.chunkExists(dir, -1, -1));
        assertFalse(RegionIndex.chunkExists(dir, 0, 0));
        assertFalse(RegionIndex.chunkExists(dir, -2, -1));
    }

    @Test
    void truncatedHeaderIsTreatedAsAbsent() throws IOException {
        Path file = dir.resolve("r.0.0.mca");
        Files.write(file, new byte[16]);
        RegionIndex.invalidate();
        assertFalse(RegionIndex.chunkExists(dir, 0, 0));
    }

    @Test
    void cachePicksUpChunksWrittenAfterFirstProbe() throws IOException {
        assertFalse(RegionIndex.chunkExists(dir, 4, 4));
        writeRegion(0, 0, index(4, 4));
        // writeRegion invalidates; the new state must be visible.
        assertTrue(RegionIndex.chunkExists(dir, 4, 4));
    }
}
