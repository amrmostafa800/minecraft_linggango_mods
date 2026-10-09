package com.linggango.opt.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * The client's persisted store of datapack payloads.
 *
 * <p>A payload is stored under a channel ({@code recipes} / {@code tags}) with the SHA-256 of its
 * exact vanilla bytes. Replaying the bytes later reconstructs the same packet object the server
 * would have sent, so no custom serialisation is introduced for the packet body itself.
 */
public final class DatapackCache {

    public enum Channel {
        RECIPES("recipes"),
        TAGS("tags");

        private final String id;

        Channel(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Entry(String hash, byte[] data) {
    }

    private final Map<Channel, Entry> entries = new EnumMap<>(Channel.class);

    // ---- in-memory access -------------------------------------------------

    public synchronized Entry get(Channel channel) {
        return entries.get(channel);
    }

    public synchronized void put(Channel channel, Entry entry) {
        entries.put(channel, entry);
    }

    public synchronized boolean matches(Channel channel, String hash) {
        Entry e = entries.get(channel);
        return e != null && e.hash().equals(hash);
    }

    public synchronized Map<Channel, Entry> snapshot() {
        return new EnumMap<>(entries);
    }

    public synchronized void replaceAll(Map<Channel, Entry> loaded) {
        entries.clear();
        entries.putAll(loaded);
    }

    // ---- disk persistence (client) ---------------------------------------

    public static DatapackCache load(Path file) {
        DatapackCache cache = new DatapackCache();
        if (!Files.isRegularFile(file)) {
            return cache;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 0x4C47_4F50) { // "LGOP"
                return cache;
            }
            int channels = in.readInt();
            for (int i = 0; i < channels; i++) {
                String id = in.readUTF();
                String hash = in.readUTF();
                int len = in.readInt();
                if (len < 0 || len > 64 * 1024 * 1024) {
                    return new DatapackCache();
                }
                byte[] data = in.readNBytes(len);
                if (data.length != len) {
                    return new DatapackCache();
                }
                Channel channel = channelOf(id);
                if (channel != null) {
                    cache.entries.put(channel, new Entry(hash, data));
                }
            }
        } catch (IOException | RuntimeException e) {
            // A corrupt cache must never break login. Start clean.
            return new DatapackCache();
        }
        return cache;
    }

    public void store(Path file) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(0x4C47_4F50);
                Map<Channel, Entry> snap = snapshot();
                out.writeInt(snap.size());
                for (Map.Entry<Channel, Entry> e : snap.entrySet()) {
                    out.writeUTF(e.getKey().id());
                    out.writeUTF(e.getValue().hash());
                    out.writeInt(e.getValue().data().length);
                    out.write(e.getValue().data());
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Persistence is best-effort; losing it only costs bytes, never correctness.
        }
    }

    private static Channel channelOf(String id) {
        for (Channel c : Channel.values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }
}
