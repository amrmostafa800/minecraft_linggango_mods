package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the region-header reader that lets the search tell "already generated
 * on disk" from "would have to be generated", without loading the chunk.
 *
 * <p>Region file format: the first 4096 bytes are 1024 big-endian 3-byte sector
 * offsets (the 4th byte is the sector count). A zero offset means the chunk was
 * never written. The reader only inspects those offsets.
 */
class ChunkIndexTest {

    private Path dir;

    @BeforeEach
    void makeTempRegionDir() throws IOException {
        dir = Files.createTempDirectory("rtp-chunkindex");
    }

    /** Writes a region file whose header marks exactly the given chunks present. */
    private void writeRegion(int regionX, int regionZ, int[][] presentChunks) throws IOException {
        byte[] header = new byte[8192];
        for (int[] chunk : presentChunks) {
            int localX = chunk[0] & 31;
            int localZ = chunk[1] & 31;
            int entry = (localX + localZ * 32) * 4;
            // A non-zero 3-byte sector offset marks the chunk as written.
            header[entry] = 0;
            header[entry + 1] = 0;
            header[entry + 2] = 1;
        }
        Path file = dir.resolve("r." + regionX + "." + regionZ + ".mca");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(header));
        }
    }

    @Test
    void missingRegionFileMeansNoChunk() {
        assertFalse(ChunkIndex.chunkExists(dir, 0, 0),
                "a chunk in a region file that does not exist is not on disk");
    }

    @Test
    void readsPresentAndAbsentChunks() throws IOException {
        writeRegion(0, 0, new int[][] {{0, 0}, {5, 7}});

        assertTrue(ChunkIndex.chunkExists(dir, 0, 0), "a marked chunk must read as present");
        assertTrue(ChunkIndex.chunkExists(dir, 5, 7), "a marked chunk must read as present");
        assertFalse(ChunkIndex.chunkExists(dir, 1, 1), "an unmarked chunk must read as absent");
    }

    @Test
    void neighbourhoodRequiresEveryChunkInTheMargin() throws IOException {
        // An interior chunk, so the whole 3x3 neighbourhood lives in this file.
        writeRegion(0, 0, new int[][] {{16, 16}});

        assertTrue(ChunkIndex.chunkExists(dir, 16, 16), "the centre chunk is present");
        assertFalse(ChunkIndex.neighborhoodOnDisk(dir, 16, 16, 1),
                "a 3x3 gate must fail when a neighbour is missing");
    }

    @Test
    void neighbourhoodPassesWhenFullyPopulated() throws IOException {
        int[][] all = new int[9][];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                all[i++] = new int[] {16 + dx, 16 + dz};
            }
        }
        writeRegion(0, 0, all);

        assertTrue(ChunkIndex.neighborhoodOnDisk(dir, 16, 16, 1),
                "a fully populated 3x3 neighbourhood must pass");
    }

    @Test
    void neighbourhoodReadsAdjacentRegionFiles() throws IOException {
        // Chunk (32,16) sits in region (1,0). Its 3x3 neighbourhood spans chunks
        // x=31..33, z=15..17: x=31 lives in region (0,0), x=32..33 in region
        // (1,0). Both files must be consulted for the gate to pass.
        writeRegion(1, 0, new int[][] {
                {32, 15}, {32, 16}, {32, 17},
                {33, 15}, {33, 16}, {33, 17}});
        writeRegion(0, 0, new int[][] {{31, 15}, {31, 16}, {31, 17}});

        assertTrue(ChunkIndex.neighborhoodOnDisk(dir, 32, 16, 1),
                "a neighbourhood spanning two region files must still pass");
    }

    @Test
    void zeroMarginChecksOnlyTheCentre() throws IOException {
        writeRegion(0, 0, new int[][] {{16, 16}});

        assertTrue(ChunkIndex.neighborhoodOnDisk(dir, 16, 16, 0),
                "a zero margin only requires the centre chunk");
    }
}
