package com.linggango.sync.net.delta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The ordered set of fields a delta is computed against.
 *
 * <p>Field order is part of the wire format, so a schema must be identical on both sides.
 * It is defined in code by the owning adapter rather than negotiated, which keeps the
 * format stable and avoids a schema-exchange round trip.
 */
public final class DeltaSchema {

    private final String id;
    private final List<DeltaField> fields;
    private final Map<String, Integer> index;

    public DeltaSchema(String id, List<DeltaField> fields) {
        this.id = Objects.requireNonNull(id, "id");
        this.fields = List.copyOf(fields);
        Map<String, Integer> lookup = new LinkedHashMap<>();
        for (int i = 0; i < this.fields.size(); i++) {
            DeltaField field = this.fields.get(i);
            if (lookup.put(field.name(), i) != null) {
                throw new IllegalArgumentException("Duplicate field " + field.name() + " in schema " + id);
            }
        }
        this.index = Map.copyOf(lookup);
    }

    public static DeltaSchema of(String id, DeltaField... fields) {
        return new DeltaSchema(id, List.of(fields));
    }

    public String id() {
        return id;
    }

    public List<DeltaField> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }

    public DeltaField field(int position) {
        return fields.get(position);
    }

    /** @return the field position, or -1 when this schema does not carry the name. */
    public int indexOf(String name) {
        Integer found = index.get(name);
        return found == null ? -1 : found;
    }

    /** Builds a value array from a name to value map, in schema order. */
    public Object[] valuesFrom(Map<String, Object> source) {
        Object[] values = new Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            DeltaField field = fields.get(i);
            values[i] = field.type().coerce(source.get(field.name()));
        }
        return values;
    }

    /** Renders a value array as a name to value map, for logging and tests. */
    public Map<String, Object> toMap(Object[] values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < fields.size() && i < values.length; i++) {
            out.put(fields.get(i).name(), values[i]);
        }
        return out;
    }

    /** Names of the fields whose value differs between two snapshots. */
    public List<String> changedFields(Object[] base, Object[] target) {
        List<String> changed = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            Object a = i < base.length ? base[i] : null;
            Object b = i < target.length ? target[i] : null;
            if (!DeltaCodec.valueEquals(fields.get(i).type(), a, b)) {
                changed.add(fields.get(i).name());
            }
        }
        return changed;
    }
}
