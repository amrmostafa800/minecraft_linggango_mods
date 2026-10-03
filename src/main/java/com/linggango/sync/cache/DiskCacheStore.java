package com.linggango.sync.cache;

import com.linggango.sync.LinggangoSync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persists the client cache as a single binary file.
 *
 * <p>Format: {@code LSYNC1} magic, a UTF string sha-256 of the payload, then
 * {@code int count} and per-entry {@code key/version/hash/length/data}. Compression is
 * optional and off by default, because most cached mod payloads are already
 * compressed NBT/JSON and gzip would cost CPU for little gain.
 *
 * <p>Corruption is expected to be survivable: a bad magic, a truncated read, or a
 * checksum mismatch discards the whole file and returns an empty map. The client then
 * simply refetches from the server.
 */
public final class DiskCacheStore implements CacheStore {

    private static final byte[] MAGIC = "LSYNC1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    private final Path file;
    private final boolean compress;

    public DiskCacheStore(Path file, boolean compress) {
        this.file = file;
        this.compress = compress;
    }

    @Override
    public Map<String, CacheEntry> load() {
        Map<String, CacheEntry> out = new LinkedHashMap<>();
        if (file == null || Files.notExists(file)) {
            return out;
        }
        try (InputStream raw = Files.newInputStream(file);
             InputStream maybeGzip = compress ? new GZIPInputStream(raw) : raw;
             DataInputStream in = new DataInputStream(new BufferedInputStream(maybeGzip))) {

            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                LinggangoSync.LOGGER.warn("Cache file {} has a bad magic; discarding", file);
                return out;
            }
            String expectedDigest = in.readUTF();
            byte[] payload = in.readAllBytes();
            if (!Hashes.sha256(payload).equals(expectedDigest)) {
                LinggangoSync.LOGGER.warn("Cache file {} failed its integrity check; discarding", file);
                return out;
            }

            try (DataInputStream body = new DataInputStream(new java.io.ByteArrayInputStream(payload))) {
                int count = body.readInt();
                long now = System.currentTimeMillis();
                for (int i = 0; i < count; i++) {
                    String key = body.readUTF();
                    long version = body.readLong();
                    String hash = body.readUTF();
                    int length = body.readInt();
                    if (length < 0 || length > MAX_ENTRY_BYTES) {
                        LinggangoSync.LOGGER.warn("Cache entry {} has an implausible length {}; discarding file", key, length);
                        return Map.of();
                    }
                    byte[] data = new byte[length];
                    body.readFully(data);
                    out.put(key, new CacheEntry(key, version, hash, data, now));
                }
            }
        } catch (IOException | RuntimeException e) {
            LinggangoSync.LOGGER.warn("Could not read cache file {} ({}); starting empty", file, e.toString());
            return Map.of();
        }
        return out;
    }

    @Override
    public void save(Map<String, CacheEntry> entries) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());

            java.io.ByteArrayOutputStream bodyBuffer = new java.io.ByteArrayOutputStream();
            try (DataOutputStream body = new DataOutputStream(bodyBuffer)) {
                body.writeInt(entries.size());
                for (CacheEntry entry : entries.values()) {
                    body.writeUTF(entry.key());
                    body.writeLong(entry.version());
                    body.writeUTF(entry.hash() == null ? "" : entry.hash());
                    byte[] data = entry.data();
                    body.writeInt(data.length);
                    body.write(data);
                }
            }
            byte[] payload = bodyBuffer.toByteArray();
            String digest = Hashes.sha256(payload);

            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream raw = Files.newOutputStream(tmp);
                 OutputStream maybeGzip = compress ? new GZIPOutputStream(raw) : raw;
                 DataOutputStream out = new DataOutputStream(new BufferedOutputStream(maybeGzip))) {
                out.write(MAGIC);
                out.writeUTF(digest);
                out.write(payload);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LinggangoSync.LOGGER.warn("Could not write cache file {} ({})", file, e.toString());
        }
    }

    public Path file() {
        return file;
    }
}
