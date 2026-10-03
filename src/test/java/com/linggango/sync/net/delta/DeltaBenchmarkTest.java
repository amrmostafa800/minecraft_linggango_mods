package com.linggango.sync.net.delta;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the actual wire saving of delta synchronisation, rather than asserting a number
 * that was guessed. The results are printed so the numbers in {@code docs/BENCHMARKS.md}
 * can be reproduced on any machine.
 *
 * <p>This is a code-level measurement: it compares the bytes this mod would put on the wire
 * for the same machine state transitions. It deliberately does not claim anything about a
 * live server, which is measured separately by the two-client harness.
 */
class DeltaBenchmarkTest {

    /** A plausible Mekanism-style machine: 5 hot fields plus a 3 KB inventory blob. */
    private static final DeltaSchema MACHINE = DeltaSchema.of("bench:machine",
            DeltaField.lastWins("energy", DeltaFieldType.LONG),
            DeltaField.lastWins("fluid", DeltaFieldType.INT),
            DeltaField.lastWins("gas", DeltaFieldType.INT),
            DeltaField.lastWins("progress", DeltaFieldType.FLOAT),
            DeltaField.lastWins("running", DeltaFieldType.BOOLEAN),
            DeltaField.lastWins("inventory", DeltaFieldType.BYTES));

    private static Object[] state(long energy, int fluid, int gas, float progress, boolean running) {
        byte[] inventory = new byte[3072];
        java.util.Arrays.fill(inventory, (byte) 0x5A);
        return new Object[]{energy, fluid, gas, progress, running, inventory};
    }

    @Test
    void deltaEncodingSavesTheMajorityOfBytesForATypicalMachine() {
        final int ticks = 1000;
        Object[] current = state(1_000_000L, 12_000, 4_000, 0f, true);

        long fullBytes = 0;
        long deltaBytes = 0;
        long packetsSentByDelta = 0;

        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        Object[] clientCopy = null;
        float progress = 0f;

        for (int tick = 0; tick < ticks; tick++) {
            // Energy ticks up every tick; progress every 4th. The inventory blob never
            // changes, which is the whole point: it must not be retransmitted.
            long energy = 1_000_000L + tick * 1_000L;
            if (tick % 4 == 0) {
                progress = (tick / 4) * 0.5f;
            }
            current = state(energy, 12_000, 4_000, progress, true);

            // What a naive implementation would send: the whole object every tick.
            fullBytes += serialiseWholeObject(current);

            sync.update(current);
            byte[] payload = sync.flushIfDue(tick * 50L);
            if (payload != null) {
                deltaBytes += payload.length;
                packetsSentByDelta++;
                clientCopy = current;
            }
        }
        assertTrue(clientCopy != null);

        double savingPercent = 100.0 * (fullBytes - deltaBytes) / fullBytes;
        System.out.printf(Locale.ROOT,
                "%nDelta benchmark (%d ticks)%n"
                        + "  whole-object encoding : %d bytes in %d packets (avg %d B)%n"
                        + "  delta encoding        : %d bytes in %d packets (avg %d B)%n"
                        + "  reduction             : %.1f%%%n",
                ticks,
                fullBytes, ticks, fullBytes / ticks,
                deltaBytes, packetsSentByDelta, packetsSentByDelta == 0 ? 0 : deltaBytes / packetsSentByDelta,
                savingPercent);

        assertTrue(savingPercent > 90.0,
                "delta encoding should remove the bulk of the traffic, was " + savingPercent + '%');
        assertTrue(deltaBytes < fullBytes / 10,
                "delta payloads should be an order of magnitude smaller");
    }

    @Test
    void unchangedMachineCostsNothingAtAll() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        Object[] frozen = state(500L, 10, 20, 1f, true);

        sync.update(frozen);
        byte[] first = sync.flush();
        assertTrue(first.length > 0, "the first send is a full snapshot");

        long after = 0;
        for (int i = 0; i < 5000; i++) {
            sync.update(frozen);
            byte[] payload = sync.flushIfDue(i * 50L);
            if (payload != null) {
                after += payload.length;
            }
        }
        System.out.printf(Locale.ROOT,
                "%nIdle machine: first snapshot %d B, next 5000 ticks %d B%n", first.length, after);
        assertTrue(after == 0, "an idle machine must generate no traffic");
    }

    @Test
    void coalescingReducesPacketCountForFastOscillation() {
        DeltaSchema gauge = DeltaSchema.of("bench:gauge",
                DeltaField.lastWins("value", DeltaFieldType.INT));

        final int updates = 2000;
        long uncoalescedPackets = 0;
        long coalescedPackets = 0;

        DeltaSyncState immediate = new DeltaSyncState(gauge, 0);
        DeltaSyncState coalesced = new DeltaSyncState(gauge, 500);

        for (int i = 0; i < updates; i++) {
            long now = i * 50L; // one update every 50 ms
            immediate.update(new Object[]{i});
            if (immediate.flushIfDue(now) != null) {
                uncoalescedPackets++;
            }
            coalesced.update(new Object[]{i});
            if (coalesced.flushIfDue(now) != null) {
                coalescedPackets++;
            }
        }

        System.out.printf(Locale.ROOT,
                "%nCoalescing (%d updates over %d ms)%n"
                        + "  without coalescing : %d packets%n"
                        + "  with 500 ms window : %d packets%n"
                        + "  reduction          : %.1f%%%n",
                updates, updates * 50, uncoalescedPackets, coalescedPackets,
                100.0 * (uncoalescedPackets - coalescedPackets) / uncoalescedPackets);

        assertTrue(coalescedPackets < uncoalescedPackets / 5,
                "a 500 ms window should collapse the majority of updates");
    }

    @Test
    void snapshotForAJoiningClientIsComparableToTheNaivePayload() {
        Object[] current = state(1_000_000L, 12_000, 4_000, 50f, true);
        int snapshot = DeltaCodec.encodeSnapshot(MACHINE, 1, current).length;
        int naive = serialiseWholeObject(current);

        System.out.printf(Locale.ROOT,
                "%nJoin snapshot: delta-format %d B vs naive %d B%n", snapshot, naive);

        // A join must still deliver everything, so this is a near-parity check, not a saving.
        assertTrue(snapshot <= naive * 1.1,
                "a full snapshot must not be larger than the naive encoding");
    }

    /** Stand-in for "send the whole object": NBT or a full field dump, including the blob. */
    private static int serialiseWholeObject(Object[] values) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeUTF(MACHINE.id());
                for (int i = 0; i < MACHINE.size(); i++) {
                    out.writeUTF(MACHINE.field(i).name());
                    MACHINE.field(i).type().write(out, values[i]);
                }
            }
            return buffer.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void fieldNamesAreNotRetransmittedInDeltas() {
        DeltaSchema named = DeltaSchema.of("bench:named",
                DeltaField.lastWins("aVeryLongFieldNameForEnergy", DeltaFieldType.LONG),
                DeltaField.lastWins("anotherVeryLongFieldName", DeltaFieldType.INT));

        DeltaSyncState sync = new DeltaSyncState(named, 0);
        sync.update(new Object[]{1L, 2});
        sync.flush();
        byte[] delta = sync.flushAfterUpdate(new Object[]{3L, 2});

        Delta decoded = DeltaCodec.decode(named, delta);
        assertTrue(decoded.values().containsKey(0));
        assertTrue(delta.length < 40,
                "a single-field delta must not carry field names, was " + delta.length + " bytes");

        // Demonstrates the difference: names cost bytes that a delta avoids entirely.
        assertTrue(named.field(0).name().getBytes(StandardCharsets.UTF_8).length > 20);
    }
}
