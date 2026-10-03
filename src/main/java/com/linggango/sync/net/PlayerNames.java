package com.linggango.sync.net;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the player name behind a netty {@code Channel} without hard-linking to
 * server-only classes.
 *
 * <p>A common-side mixin must not reference {@code ServerGamePacketListenerImpl}, or the
 * client would fail to load the mixin. Instead the listener is inspected by name and the
 * player field is read reflectively. The result is cached per connection, so the
 * reflective lookup runs once per login rather than once per packet.
 */
public final class PlayerNames {

    private static final Map<Object, String> CACHE = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_CONNECTIONS = 4096;
    private static final String[] PLAYER_FIELDS = {"player", "f_9743_"};

    private PlayerNames() {
    }

    /** @return the player name, or {@code null} if this connection has no player yet. */
    public static String resolve(Object connection, Object packetListener) {
        if (connection == null) {
            return null;
        }
        String cached = CACHE.get(connection);
        if (cached != null) {
            return cached;
        }
        String resolved = compute(packetListener);
        if (resolved != null) {
            if (CACHE.size() >= MAX_TRACKED_CONNECTIONS) {
                // A hard cap keeps a long-running server from accumulating one entry per
                // connection ever seen. Losing the cache only costs one reflective lookup.
                CACHE.clear();
            }
            CACHE.put(connection, resolved);
        }
        return resolved;
    }

    public static void forget(Object connection) {
        if (connection != null) {
            CACHE.remove(connection);
        }
    }

    /** Drops every cached connection belonging to this player. Called on logout. */
    public static void forgetPlayer(String playerName) {
        if (playerName != null) {
            CACHE.values().removeIf(playerName::equals);
        }
    }

    private static String compute(Object packetListener) {
        if (packetListener == null) {
            return null;
        }
        Class<?> type = packetListener.getClass();
        if (!type.getName().contains("ServerGamePacketListenerImpl")) {
            return null;
        }
        for (String fieldName : PLAYER_FIELDS) {
            try {
                Field field = findField(type, fieldName);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                Object player = field.get(packetListener);
                if (player == null) {
                    return null;
                }
                Object profile = player.getClass().getMethod("getGameProfile").invoke(player);
                Object name = profile.getClass().getMethod("getName").invoke(profile);
                return name == null ? null : name.toString();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}
