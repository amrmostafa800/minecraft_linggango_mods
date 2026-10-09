package com.linggango.opt.net;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small helpers for hashing payload bytes. */
public final class Hashes {

    private Hashes() {
    }

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

    /** Compact FNV-1a hash of a short string, used to build a cheap payload-set version. */
    public static String fnv1a(String input) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < input.length(); i++) {
            hash ^= input.charAt(i);
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }
}
