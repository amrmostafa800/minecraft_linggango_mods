package com.linggango.sync.net.delta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Encodes and decodes field-level deltas.
 *
 * <p>Wire format (all integers big-endian, matching {@code DataOutputStream}):
 * <pre>
 *   UTF   schemaId
 *   int   baseVersion        the version the receiver must already hold, 0 for a full snapshot
 *   int   targetVersion      the version this payload produces
 *   int   fieldCount
 *   per field:
 *     byte fieldIndex        position in the schema
 *     byte flags             bit 0 = full-value, bit 1 = additive
 *     &lt;typed value&gt;
 *   int   crc32              over everything above
 * </pre>
 *
 * <p>A full snapshot is a delta whose base version is 0 that carries every field. Keeping
 * one format means one code path, so a client that fell too far behind is handled by the
 * same logic as a client that just joined.
 *
 * <p>Decoding is schema-driven: field types come from the caller's {@link DeltaSchema}, so
 * the payload does not repeat them.
 */
public final class DeltaCodec {

    /** Base version meaning "apply to nothing": the payload is a full snapshot. */
    public static final int NO_BASE = 0;

    private static final int MAX_FIELDS = 4096;

    private DeltaCodec() {
    }

    /**
     * Encodes the difference between {@code previous} and {@code current}.
     *
     * @param previous the receiver's last acknowledged snapshot, or {@code null} to force a
     *                 full snapshot
     * @return the wire bytes, or an empty array when nothing changed
     */
    public static byte[] encode(DeltaSchema schema,
                                int baseVersion,
                                int targetVersion,
                                Object[] previous,
                                Object[] current) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(current, "current");

        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < schema.size(); i++) {
            Object before = previous != null && i < previous.length ? previous[i] : null;
            Object after = i < current.length ? current[i] : null;
            if (previous == null || !valueEquals(schema.field(i).type(), before, after)) {
                changed.add(i);
            }
        }
        if (changed.isEmpty()) {
            return new byte[0];
        }

        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 + changed.size() * 8);
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeUTF(schema.id());
                out.writeInt(previous == null ? NO_BASE : baseVersion);
                out.writeInt(targetVersion);
                out.writeInt(changed.size());
                for (int position : changed) {
                    DeltaField field = schema.field(position);
                    out.writeByte(position);
                    out.writeByte(flagsFor(field, previous == null));
                    field.type().write(out, current[position]);
                }
            }
            return appendCrc(buffer.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode delta for " + schema.id(), e);
        }
    }

    /** A full snapshot: every field, base version {@link #NO_BASE}. */
    public static byte[] encodeSnapshot(DeltaSchema schema, int targetVersion, Object[] current) {
        return encode(schema, NO_BASE, targetVersion, null, current);
    }

    private static byte flagsFor(DeltaField field, boolean fullSnapshot) {
        int flags = 1;
        if (!fullSnapshot && field.merge() == DeltaMerge.ADDITIVE) {
            flags |= 2;
        }
        return (byte) flags;
    }

    /**
     * Decodes a payload against {@code schema}.
     *
     * @throws DeltaFormatException if the payload is short, truncated, has a bad checksum,
     *                              or was produced by a different schema
     */
    public static Delta decode(DeltaSchema schema, byte[] payload) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(payload, "payload");
        if (payload.length < 6) {
            throw new DeltaFormatException("payload too short: " + payload.length);
        }
        int crcOffset = payload.length - 4;
        if (readInt(payload, crcOffset) != crc32(payload, crcOffset)) {
            throw new DeltaFormatException("checksum mismatch");
        }

        byte[] body = Arrays.copyOf(payload, crcOffset);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            String schemaId = in.readUTF();
            if (!schema.id().equals(schemaId)) {
                throw new DeltaFormatException("schema mismatch: expected " + schema.id() + " but payload is " + schemaId);
            }
            int baseVersion = in.readInt();
            int targetVersion = in.readInt();
            int fieldCount = in.readInt();
            if (fieldCount < 0 || fieldCount > MAX_FIELDS) {
                throw new DeltaFormatException("implausible field count " + fieldCount);
            }

            Map<Integer, Object> values = new LinkedHashMap<>();
            Set<Integer> additive = new LinkedHashSet<>();
            for (int i = 0; i < fieldCount; i++) {
                int index = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                if (index >= schema.size()) {
                    throw new DeltaFormatException("field index " + index + " outside schema of size " + schema.size());
                }
                values.put(index, schema.field(index).type().read(in));
                if ((flags & 2) != 0) {
                    additive.add(index);
                }
            }
            return new Delta(schemaId, baseVersion, targetVersion, values, additive);
        } catch (IOException e) {
            throw new DeltaFormatException("could not parse delta: " + e.getMessage(), e);
        }
    }

    static int crc32(byte[] data, int length) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, 0, length);
        return (int) crc.getValue();
    }

    private static byte[] appendCrc(byte[] payload) {
        byte[] out = Arrays.copyOf(payload, payload.length + 4);
        writeInt(out, payload.length, crc32(payload, payload.length));
        return out;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    static boolean valueEquals(DeltaFieldType type, Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (type == DeltaFieldType.BYTES) {
            return Arrays.equals((byte[]) a, (byte[]) b);
        }
        if (type == DeltaFieldType.FLOAT) {
            return Float.compare(((Number) a).floatValue(), ((Number) b).floatValue()) == 0;
        }
        if (type == DeltaFieldType.DOUBLE) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue()) == 0;
        }
        return a.equals(b);
    }
}
