package com.linggango.sync.net.delta;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The value types a {@link DeltaSchema} can carry.
 *
 * <p>Only fixed-width numeric types and byte arrays are offered for high-frequency fields.
 * Strings are available but cost a length prefix, so they belong in on-demand payloads
 * rather than per-tick deltas.
 */
public enum DeltaFieldType {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    UTF8,
    BYTES;

    void write(DataOutputStream out, Object value) throws IOException {
        switch (this) {
            case BOOLEAN -> out.writeBoolean(value != null && (Boolean) value);
            case INT -> out.writeInt(value == null ? 0 : (Integer) value);
            case LONG -> out.writeLong(value == null ? 0L : (Long) value);
            case FLOAT -> out.writeFloat(value == null ? 0f : (Float) value);
            case DOUBLE -> out.writeDouble(value == null ? 0d : (Double) value);
            case UTF8 -> out.writeUTF(value == null ? "" : value.toString());
            case BYTES -> {
                byte[] bytes = value == null ? new byte[0] : (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
            }
        }
    }

    Object read(DataInputStream in) throws IOException {
        return switch (this) {
            case BOOLEAN -> in.readBoolean();
            case INT -> in.readInt();
            case LONG -> in.readLong();
            case FLOAT -> in.readFloat();
            case DOUBLE -> in.readDouble();
            case UTF8 -> in.readUTF();
            case BYTES -> {
                int length = in.readInt();
                if (length < 0 || length > 16 * 1024 * 1024) {
                    throw new IOException("implausible byte field length " + length);
                }
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                yield bytes;
            }
        };
    }

    /** Coerces a loosely typed value (for example from JSON) into this type. */
    public Object coerce(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (this) {
                case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
                case INT -> raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString());
                case LONG -> raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
                case FLOAT -> raw instanceof Number n ? n.floatValue() : Float.parseFloat(raw.toString());
                case DOUBLE -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString());
                case UTF8 -> raw.toString();
                case BYTES -> raw instanceof byte[] b ? b : raw.toString().getBytes(StandardCharsets.UTF_8);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }
}
