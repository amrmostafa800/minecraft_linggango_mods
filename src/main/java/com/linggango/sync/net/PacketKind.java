package com.linggango.sync.net;

/**
 * The protocol phase a packet belongs to, derived from its class package.
 *
 * <p>This mirrors the vanilla {@code ConnectionProtocol} split without depending on the
 * game classes, so the monitor can be exercised in plain unit tests.
 */
public enum PacketKind {
    HANDSHAKE,
    STATUS,
    LOGIN,
    CONFIGURATION,
    PLAY,
    COMMON,
    UNKNOWN;

    public static PacketKind ofClassName(String className) {
        if (className == null) {
            return UNKNOWN;
        }
        if (className.contains("network.protocol.handshake")) {
            return HANDSHAKE;
        }
        if (className.contains("network.protocol.status")) {
            return STATUS;
        }
        if (className.contains("network.protocol.login")) {
            return LOGIN;
        }
        if (className.contains("network.protocol.configuration")) {
            return CONFIGURATION;
        }
        if (className.contains("network.protocol.game")) {
            return PLAY;
        }
        if (className.contains("network.protocol.common")) {
            return COMMON;
        }
        return UNKNOWN;
    }
}
