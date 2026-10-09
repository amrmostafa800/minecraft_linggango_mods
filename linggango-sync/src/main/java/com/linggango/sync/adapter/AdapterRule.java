package com.linggango.sync.adapter;

import com.linggango.sync.config.SyncPolicy;

/**
 * A declared, auditable claim about one data type owned by one mod.
 *
 * @param namespace     the owning mod id
 * @param dataType      a stable identifier within the namespace
 * @param defaultPolicy the policy this adapter recommends
 * @param rationale     why this is safe (or why it is not). Rendered by
 *                      {@code /linggango-net adapters} so an operator can audit the claim
 *                      before enabling it.
 * @param safe          {@code false} marks a data type the adapter deliberately refuses to
 *                      optimise. It is still registered, at
 *                      {@link SyncPolicy#SERVER_AUTHORITATIVE}, so the refusal is visible.
 */
public record AdapterRule(String namespace,
                          String dataType,
                          SyncPolicy defaultPolicy,
                          String rationale,
                          boolean safe) {

    public static AdapterRule safe(String namespace, String dataType, SyncPolicy policy, String rationale) {
        return new AdapterRule(namespace, dataType, policy, rationale, true);
    }

    public static AdapterRule refused(String namespace, String dataType, String rationale) {
        return new AdapterRule(namespace, dataType, SyncPolicy.SERVER_AUTHORITATIVE, rationale, false);
    }
}
