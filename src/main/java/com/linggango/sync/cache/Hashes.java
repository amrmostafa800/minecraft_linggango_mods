package com.linggango.sync.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content hashing for cache versions.
 *
 * <p>CRC32 is used rather than a cryptographic digest: this is a private server, the hash
 * only needs to detect accidental divergence, and CRC32 is cheap enough to run on every
 * machine update. It is explicitly not a security boundary.
 */
public final class Hashes {

    private Hashes() {
    }

    public static String of(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, 0, data.length);
        return Long.toHexString(crc.getValue());
    }

    public static String of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /** SHA-256 hex, used for the on-disk cache integrity footer. */
    public static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(data);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
