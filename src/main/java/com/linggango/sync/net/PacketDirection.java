package com.linggango.sync.net;

public enum PacketDirection {
    SERVER_TO_CLIENT,
    CLIENT_TO_SERVER,
    UNKNOWN;

    public String label() {
        return this == SERVER_TO_CLIENT ? "S->C" : this == CLIENT_TO_SERVER ? "C->S" : "?";
    }
}
