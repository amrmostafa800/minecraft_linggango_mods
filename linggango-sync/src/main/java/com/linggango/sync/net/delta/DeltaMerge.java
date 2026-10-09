package com.linggango.sync.net.delta;

/**
 * How a receiver combines an incoming field value with what it already has.
 *
 * <p>{@link #LAST_WINS} is the safe default: it reproduces exactly what the server sent.
 * The other modes exist because coalescing several ticks into one packet would otherwise
 * lose a change, and losing a change is precisely what the design forbids.
 */
public enum DeltaMerge {

    /** Replace the local value with the received one. Correct for gauges and setpoints. */
    LAST_WINS,

    /** Take the larger value. Correct for counters that only ever increase, such as lifetime output. */
    MONOTONIC,

    /**
     * Add the received value to the local one. Correct for accumulators that the server
     * already coalesced (for example "12 mB produced across the ticks I skipped"), so the
     * client's total still matches the server's.
     */
    ADDITIVE
}
