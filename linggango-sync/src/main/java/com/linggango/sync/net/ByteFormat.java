package com.linggango.sync.net;

/** Human-readable byte and rate formatting shared by the monitor and the commands. */
public final class ByteFormat {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private ByteFormat() {
    }

    public static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        double scaled = value;
        int unit = 0;
        while (scaled >= 1024 && unit < UNITS.length - 1) {
            scaled /= 1024.0;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", scaled, UNITS[unit]);
    }

    public static String perMinute(long bytes, long windowSeconds) {
        if (windowSeconds <= 0) {
            return "0 B/min";
        }
        return bytes(bytes * 60L / windowSeconds) + "/min";
    }

    public static String duration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + "m " + (seconds % 60) + "s";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    public static String count(long value) {
        return String.format(java.util.Locale.ROOT, "%,d", value);
    }
}
