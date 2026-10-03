package com.linggango.sync.net.delta;

import java.util.Objects;

/**
 * One named, typed field in a {@link DeltaSchema}.
 *
 * @param name   stable field name, used in logs and tests
 * @param type   wire type
 * @param merge  how a client applies an update to this field
 */
public record DeltaField(String name, DeltaFieldType type, DeltaMerge merge) {

    public DeltaField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(merge, "merge");
    }

    public static DeltaField lastWins(String name, DeltaFieldType type) {
        return new DeltaField(name, type, DeltaMerge.LAST_WINS);
    }

    public static DeltaField monotonic(String name, DeltaFieldType type) {
        return new DeltaField(name, type, DeltaMerge.MONOTONIC);
    }

    public static DeltaField additive(String name, DeltaFieldType type) {
        return new DeltaField(name, type, DeltaMerge.ADDITIVE);
    }
}
