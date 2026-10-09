package com.linggango.opt.bench;

import com.linggango.opt.LinggangoOptimizer;
import com.linggango.opt.config.OptConfig;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts bytes <em>after</em> zlib, at the compression stage.
 *
 * <p>The first mod's monitor hooks the packet encoder, which runs before compression; its numbers
 * are a truthful measure of how much structure is being serialised but overstate wire cost by the
 * compression ratio. This meter fills that gap.
 */
public final class WireStats {

    private static final AtomicLong frames = new AtomicLong();
    private static final AtomicLong wireBytes = new AtomicLong();
    private static final AtomicLong rawBytes = new AtomicLong();
    private static final AtomicLong uncompressedFrames = new AtomicLong();

    private WireStats() {
    }

    public static void record(int rawLength, int writtenLength) {
        if (!OptConfig.COMMON.wireMonitor.get()) {
            return;
        }
        frames.incrementAndGet();
        wireBytes.addAndGet(writtenLength);
        rawBytes.addAndGet(rawLength);
        if (writtenLength == rawLength) {
            uncompressedFrames.incrementAndGet();
        }
        if (OptConfig.COMMON.wireLogPerPacket.get()) {
            LinggangoOptimizer.LOGGER.info("[opt-wire] {} raw={} written={}",
                    FMLEnvironment.dist.isClient() ? "C->S" : "S->C", rawLength, writtenLength);
        }
    }

    public static String report() {
        long f = frames.get();
        long w = wireBytes.get();
        long r = rawBytes.get();
        double ratio = r == 0 ? 0.0 : ((double) r / Math.max(1, w));
        return String.format(
                "[opt-wire] frames=%d  raw=%.2f MB  wire=%.2f MB  compression=%.1fx  uncompressed=%d",
                f, r / 1048576.0, w / 1048576.0, ratio, uncompressedFrames.get());
    }

    public static long wireBytes() {
        return wireBytes.get();
    }

    public static void reset() {
        frames.set(0);
        wireBytes.set(0);
        rawBytes.set(0);
        uncompressedFrames.set(0);
    }
}
