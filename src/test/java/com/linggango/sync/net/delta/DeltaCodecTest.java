package com.linggango.sync.net.delta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the delta wire format: change detection, round trips, corruption handling and
 * coalescing behaviour.
 */
class DeltaCodecTest {

    private static final DeltaSchema MACHINE = DeltaSchema.of("mekanism:machine",
            DeltaField.lastWins("energy", DeltaFieldType.LONG),
            DeltaField.lastWins("fluid", DeltaFieldType.INT),
            DeltaField.lastWins("progress", DeltaFieldType.FLOAT),
            DeltaField.monotonic("lifetimeOutput", DeltaFieldType.LONG),
            DeltaField.lastWins("running", DeltaFieldType.BOOLEAN));

    private static Object[] state(long energy, int fluid, float progress, long lifetime, boolean running) {
        return new Object[]{energy, fluid, progress, lifetime, running};
    }

    private static byte[] updateAndFlush(DeltaSyncState sync, Object[] values) {
        sync.update(values);
        return sync.flush();
    }

    @Test
    void onlyChangedFieldsAreEncoded() {
        Object[] before = state(500_000L, 12_000, 50f, 1_000L, true);
        Object[] after = state(510_000L, 12_000, 50f, 1_000L, true);

        Delta delta = DeltaCodec.decode(MACHINE, DeltaCodec.encode(MACHINE, 41, 42, before, after));

        assertEquals(1, delta.fieldCount(), "only energy changed");
        assertEquals(510_000L, delta.values().get(0));
        assertEquals(41, delta.baseVersion());
        assertEquals(42, delta.targetVersion());
    }

    @Test
    void unchangedStateProducesNoPayload() {
        Object[] before = state(1L, 2, 3f, 4L, true);
        byte[] payload = DeltaCodec.encode(MACHINE, 1, 2, before, state(1L, 2, 3f, 4L, true));
        assertEquals(0, payload.length, "nothing changed, so nothing should be sent");
    }

    @Test
    void snapshotCarriesEveryFieldAndBaseZero() {
        Delta delta = DeltaCodec.decode(MACHINE, DeltaCodec.encodeSnapshot(MACHINE, 1, state(1L, 2, 3f, 4L, false)));

        assertTrue(delta.isSnapshot());
        assertEquals(MACHINE.size(), delta.fieldCount());
        assertEquals(1L, delta.values().get(0));
        assertEquals(false, delta.values().get(4));
    }

    @Test
    void allTypesRoundTripExactly() {
        DeltaSchema schema = DeltaSchema.of("test:types",
                DeltaField.lastWins("b", DeltaFieldType.BOOLEAN),
                DeltaField.lastWins("i", DeltaFieldType.INT),
                DeltaField.lastWins("l", DeltaFieldType.LONG),
                DeltaField.lastWins("f", DeltaFieldType.FLOAT),
                DeltaField.lastWins("d", DeltaFieldType.DOUBLE),
                DeltaField.lastWins("s", DeltaFieldType.UTF8),
                DeltaField.lastWins("y", DeltaFieldType.BYTES));

        Object[] values = {true, Integer.MIN_VALUE, Long.MAX_VALUE, 1.5f, -2.25d,
                "hello \u00e9\u00fc", new byte[]{1, 2, 3}};
        Delta decoded = DeltaCodec.decode(schema, DeltaCodec.encodeSnapshot(schema, 1, values));

        assertEquals(true, decoded.values().get(0));
        assertEquals(Integer.MIN_VALUE, decoded.values().get(1));
        assertEquals(Long.MAX_VALUE, decoded.values().get(2));
        assertEquals(1.5f, decoded.values().get(3));
        assertEquals(-2.25d, decoded.values().get(4));
        assertEquals("hello \u00e9\u00fc", decoded.values().get(5));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) decoded.values().get(6));
    }

    @Test
    void corruptedPayloadIsRejected() {
        byte[] payload = DeltaCodec.encodeSnapshot(MACHINE, 1, state(1L, 2, 3f, 4L, true));
        payload[3] ^= 0x7F;

        DeltaFormatException error = assertThrows(DeltaFormatException.class,
                () -> DeltaCodec.decode(MACHINE, payload));
        assertTrue(error.getMessage().contains("checksum"), error.getMessage());
    }

    @Test
    void truncatedPayloadIsRejected() {
        byte[] payload = DeltaCodec.encodeSnapshot(MACHINE, 1, state(1L, 2, 3f, 4L, true));
        byte[] truncated = java.util.Arrays.copyOf(payload, payload.length - 3);
        assertThrows(DeltaFormatException.class, () -> DeltaCodec.decode(MACHINE, truncated));
    }

    @Test
    void schemaMismatchIsRejected() {
        DeltaSchema other = DeltaSchema.of("create:contraption",
                DeltaField.lastWins("speed", DeltaFieldType.FLOAT));
        byte[] payload = DeltaCodec.encodeSnapshot(other, 1, new Object[]{1.0f});

        DeltaFormatException error = assertThrows(DeltaFormatException.class,
                () -> DeltaCodec.decode(MACHINE, payload));
        assertTrue(error.getMessage().contains("schema mismatch"), error.getMessage());
    }

    @Test
    void emptyPayloadIsRejected() {
        assertThrows(DeltaFormatException.class, () -> DeltaCodec.decode(MACHINE, new byte[0]));
    }

    @Test
    void changedFieldsReportsNames() {
        List<String> changed = MACHINE.changedFields(state(1L, 2, 3f, 4L, true), state(9L, 2, 3f, 4L, false));
        assertEquals(List.of("energy", "running"), changed);
    }

    @Test
    void valuesFromMapCoercesTypes() {
        Object[] values = MACHINE.valuesFrom(Map.of(
                "energy", 12345,
                "fluid", "678",
                "progress", 0.5,
                "lifetimeOutput", 7,
                "running", true));

        assertEquals(12345L, values[0]);
        assertEquals(678, values[1]);
        assertEquals(0.5f, values[2]);
        assertEquals(7L, values[3]);
        assertEquals(true, values[4]);
    }

    @Test
    void uncoercibleValueBecomesNullRatherThanThrowing() {
        Object[] values = MACHINE.valuesFrom(Map.of("energy", "not-a-number"));
        assertNull(values[0]);
    }

    @Test
    void duplicateFieldNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> DeltaSchema.of("bad",
                DeltaField.lastWins("a", DeltaFieldType.INT),
                DeltaField.lastWins("a", DeltaFieldType.INT)));
    }

    @Test
    void syncStateCoalescesRapidUpdatesToTheNewest() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        byte[] first = updateAndFlush(sync, state(1L, 0, 0f, 0L, false));
        assertTrue(first.length > 0);

        // Four rapid changes before the next send.
        sync.update(state(2L, 0, 0f, 0L, false));
        sync.update(state(3L, 0, 0f, 0L, false));
        sync.update(state(4L, 0, 0f, 0L, false));
        sync.update(state(5L, 0, 0f, 0L, false));
        Delta delta = DeltaCodec.decode(MACHINE, sync.flush());

        assertEquals(1, delta.fieldCount(), "intermediate energy values must collapse to one field");
        assertEquals(5L, delta.values().get(0), "the newest value must win");
        assertEquals(4, sync.fieldsCoalesced(),
                "each of the four updates replaced a value the previous one had not yet sent");
    }

    @Test
    void coalescingDoesNotSwallowADifferentField() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        updateAndFlush(sync, state(1L, 10, 0f, 0L, false));

        sync.update(state(2L, 20, 0f, 0L, false));
        sync.update(state(3L, 30, 0f, 0L, false));
        Delta delta = DeltaCodec.decode(MACHINE, sync.flush());

        assertEquals(2, delta.fieldCount(), "energy and fluid both changed");
        assertEquals(3L, delta.values().get(0));
        assertEquals(30, delta.values().get(1));
    }

    @Test
    void coalescingWindowDefersSend() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 1000);
        sync.update(state(1L, 0, 0f, 0L, false));
        assertTrue(sync.flushIfDue(System.currentTimeMillis()).length > 0);

        sync.update(state(2L, 0, 0f, 0L, false));
        long now = System.currentTimeMillis();
        assertNull(sync.flushIfDue(now), "must wait for the coalescing window");
        assertTrue(sync.flushIfDue(now + 1001).length > 0);
    }

    @Test
    void additiveFieldIsFlaggedOnlyInDeltas() {
        DeltaSchema schema = DeltaSchema.of("test:additive",
                DeltaField.additive("produced", DeltaFieldType.INT));
        DeltaSyncState sync = new DeltaSyncState(schema, 0);

        Delta snapshot = DeltaCodec.decode(schema, updateAndFlush(sync, new Object[]{5}));
        assertTrue(snapshot.isSnapshot());
        assertTrue(snapshot.additive().isEmpty(),
                "a full snapshot carries absolute values, so additive carries no meaning");

        Delta delta = DeltaCodec.decode(schema, updateAndFlush(sync, new Object[]{12}));
        assertTrue(delta.additive().contains(0), "a coalesced delta must tell the client to accumulate");
    }

    @Test
    void resetBaselineForcesSnapshot() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        updateAndFlush(sync, state(1L, 0, 0f, 0L, false));

        sync.update(state(2L, 0, 0f, 0L, false));
        sync.resetBaseline();
        Delta delta = DeltaCodec.decode(MACHINE, sync.flush());

        assertTrue(delta.isSnapshot(), "a reconnecting client must receive a full snapshot");
        assertEquals(MACHINE.size(), delta.fieldCount());
    }

    @Test
    void syncStateSendsNothingWhenIdle() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        assertTrue(updateAndFlush(sync, state(1L, 0, 0f, 0L, false)).length > 0);
        assertNull(sync.flush(), "no change means no packet");
        assertNull(sync.flushIfDue(System.currentTimeMillis() + 10_000));
    }

    @Test
    void hasPendingChangeIsFalseUntilSomethingDiffers() {
        DeltaSyncState sync = new DeltaSyncState(MACHINE, 0);
        updateAndFlush(sync, state(1L, 2, 3f, 4L, true));
        assertFalse(sync.hasPendingChange());
        sync.update(state(1L, 2, 3f, 4L, false));
        assertTrue(sync.hasPendingChange());
    }
}
