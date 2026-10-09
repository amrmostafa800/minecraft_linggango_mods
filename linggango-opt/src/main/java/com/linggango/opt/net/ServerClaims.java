package com.linggango.opt.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-side memory of what each player last claimed to hold.
 *
 * <p>Keyed by player UUID and persisted, so a returning player can be skipped without a
 * login-phase round trip: the datapack send happens during {@code placeNewPlayer}, long before a
 * play-phase packet could arrive, so the only way to honour a claim at send time is to remember
 * it from the player's previous session.
 */
public final class ServerClaims {

    public record Claim(String hash) {
    }

    private final Map<UUID, Map<DatapackCache.Channel, Claim>> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> skips = new ConcurrentHashMap<>();
    private final AtomicLong totalSkips = new AtomicLong();

    public void claim(UUID player, DatapackCache.Channel channel, String hash) {
        byPlayer.computeIfAbsent(player, k -> new EnumMap<>(DatapackCache.Channel.class))
                .put(channel, new Claim(hash));
    }

    public Claim claim(UUID player, DatapackCache.Channel channel) {
        Map<DatapackCache.Channel, Claim> m = byPlayer.get(player);
        return m == null ? null : m.get(channel);
    }

    public boolean matches(UUID player, DatapackCache.Channel channel, String hash) {
        Claim c = claim(player, channel);
        return c != null && c.hash().equals(hash);
    }

    /** Forget a claim so the next send for this player is never skipped. */
    public void forget(UUID player, DatapackCache.Channel channel) {
        Map<DatapackCache.Channel, Claim> m = byPlayer.get(player);
        if (m != null) {
            m.remove(channel);
        }
    }

    public void recordSkip(UUID player) {
        totalSkips.incrementAndGet();
        skips.computeIfAbsent(String.valueOf(player), k -> new AtomicLong()).incrementAndGet();
    }

    public long totalSkips() {
        return totalSkips.get();
    }

    // ---- persistence ------------------------------------------------------

    public static ServerClaims load(Path file) {
        ServerClaims claims = new ServerClaims();
        if (!Files.isRegularFile(file)) {
            return claims;
        }
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != 0x4C47_53_43) { // "LGSC"
                return claims;
            }
            int players = in.readInt();
            for (int i = 0; i < players; i++) {
                UUID uuid = UUID.fromString(in.readUTF());
                int channels = in.readInt();
                Map<DatapackCache.Channel, Claim> m = new EnumMap<>(DatapackCache.Channel.class);
                for (int j = 0; j < channels; j++) {
                    String id = in.readUTF();
                    String hash = in.readUTF();
                    for (DatapackCache.Channel c : DatapackCache.Channel.values()) {
                        if (c.id().equals(id)) {
                            m.put(c, new Claim(hash));
                        }
                    }
                }
                claims.byPlayer.put(uuid, m);
            }
        } catch (IOException | RuntimeException e) {
            return new ServerClaims();
        }
        return claims;
    }

    public void store(Path file) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                out.writeInt(0x4C47_53_43);
                out.writeInt(byPlayer.size());
                for (Map.Entry<UUID, Map<DatapackCache.Channel, Claim>> e : byPlayer.entrySet()) {
                    out.writeUTF(e.getKey().toString());
                    out.writeInt(e.getValue().size());
                    for (Map.Entry<DatapackCache.Channel, Claim> c : e.getValue().entrySet()) {
                        out.writeUTF(c.getKey().id());
                        out.writeUTF(c.getValue().hash());
                    }
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Best-effort.
        }
    }
}
