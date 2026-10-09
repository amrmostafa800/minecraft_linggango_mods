package com.linggango.sync.net.delta;

/** Thrown when a delta payload is malformed, truncated or fails its checksum. */
public class DeltaFormatException extends RuntimeException {

    public DeltaFormatException(String message) {
        super(message);
    }

    public DeltaFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
