package com.linggango.sync.net.delta;

import java.util.Map;
import java.util.Set;

/**
 * A decoded delta, before it is applied to a local snapshot.
 *
 * @param schemaId      schema the sender used
 * @param baseVersion   the version the receiver must already hold, or {@link DeltaCodec#NO_BASE}
 * @param targetVersion the version this delta produces
 * @param values        field position to decoded value
 * @param additive      field positions whose merge mode is {@link DeltaMerge#ADDITIVE}
 */
public record Delta(String schemaId,
                    int baseVersion,
                    int targetVersion,
                    Map<Integer, Object> values,
                    Set<Integer> additive) {

    public boolean isSnapshot() {
        return baseVersion == DeltaCodec.NO_BASE;
    }

    public int fieldCount() {
        return values.size();
    }
}
