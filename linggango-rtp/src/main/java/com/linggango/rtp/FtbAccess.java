package com.linggango.rtp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * The single place that reaches FTB Essentials by reflection.
 *
 * <p>The mod compiles against Forge alone and declares no dependency on
 * {@code ftbessentials}, so every FTB symbol is resolved by name. All lookups
 * happen once in the static initializer and are guarded: any {@link Throwable}
 * logs a warning and leaves {@link #available()} false, turning the mod inert
 * instead of breaking startup. A pack that does not ship FTB Essentials is a
 * supported configuration, not an error.
 *
 * <p>Only the final teleport is delegated to FTB (through
 * {@code rtpTeleporter.teleport}), so FTB's cooldown, dimension blacklist,
 * warm-up and teleport history still apply.
 */
public final class FtbAccess {

    private static final String EVENTS_CLASS = "dev.ftb.mods.ftbessentials.FTBEssentialsEvents";
    private static final String PLAYER_DATA_CLASS = "dev.ftb.mods.ftbessentials.util.FTBEPlayerData";
    private static final String TELEPORTER_CLASS = "dev.ftb.mods.ftbessentials.util.WarmupCooldownTeleporter";
    private static final String TELEPORT_POS_CLASS = "dev.ftb.mods.ftbessentials.util.TeleportPos";
    private static final String TELEPORT_RESULT_CLASS =
            "dev.ftb.mods.ftbessentials.util.TeleportPos$TeleportResult";

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private static volatile boolean available;
    private static Method getOrCreate;
    private static Field rtpTeleporter;
    private static Method checkCooldown;
    private static Method teleport;
    private static Method isSuccess;
    private static Constructor<?> teleportPosCtor;

    static {
        try {
            // Resolving FTBEssentialsEvents proves the mod is present even
            // though this class does not otherwise use its RTP_EVENT field.
            Class.forName(EVENTS_CLASS);
            Class<?> playerData = Class.forName(PLAYER_DATA_CLASS);
            Class<?> player = Class.forName("net.minecraft.world.entity.player.Player");

            getOrCreate = playerData.getMethod("getOrCreate", player);
            rtpTeleporter = playerData.getField("rtpTeleporter");

            Class<?> teleporter = Class.forName(TELEPORTER_CLASS);
            checkCooldown = teleporter.getMethod("checkCooldown");
            teleport = teleporter.getMethod("teleport", ServerPlayer.class, Function.class);

            Class<?> teleportPos = Class.forName(TELEPORT_POS_CLASS);
            teleportPosCtor = teleportPos.getConstructor(ResourceKey.class, BlockPos.class);
            isSuccess = Class.forName(TELEPORT_RESULT_CLASS).getMethod("isSuccess");

            available = true;
        } catch (Throwable t) {
            warnOnce("FTB Essentials not usable; the mod stays inert", t);
            available = false;
        }
    }

    private FtbAccess() {
    }

    /** True when every FTB Essentials class this class needs resolved. */
    public static boolean available() {
        return available;
    }

    /**
     * Builds the position getter FTB's teleport expects. The getter ignores its
     * player argument and returns a {@code TeleportPos} for the fixed
     * dimension/position; when FTB is absent it returns a pure {@link Target}
     * holder instead, which is also the object returned in unit tests.
     */
    public static Function<Object, Object> positionSupplier(ResourceKey<Level> dim, BlockPos pos) {
        return ignored -> {
            Constructor<?> ctor = teleportPosCtor;
            if (ctor != null) {
                try {
                    return ctor.newInstance(dim, pos);
                } catch (Throwable t) {
                    warnOnce("TeleportPos construction failed", t);
                }
            }
            return new Target(dim, pos);
        };
    }

    /**
     * Whether a FTB cooldown currently blocks {@code /rtp} for the player.
     * Returns false when there is no cooldown or FTB is absent.
     *
     * <p>FTB only exposes the cooldown as success/failure (see
     * {@code TeleportPos.TeleportResult}); it does not expose the remaining
     * duration, so the boolean is the most this adapter can report.
     */
    public static boolean checkCooldown(ServerPlayer player) {
        if (!available) {
            return false;
        }
        try {
            Object data = playerData(player);
            if (data == null) {
                return false;
            }
            Object teleporter = rtpTeleporter.get(data);
            Object result = checkCooldown.invoke(teleporter);
            return !Boolean.TRUE.equals(isSuccess.invoke(result));
        } catch (Throwable t) {
            warnOnce("checkCooldown failed", t);
            return false;
        }
    }

    /**
     * Delegates the final teleport to FTB so its cooldown, blacklist, warm-up
     * and teleport history still apply. Returns true only when FTB reports
     * success; any failure (including FTB absent) returns false.
     */
    public static boolean teleportTo(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        if (!available) {
            return false;
        }
        try {
            Object data = playerData(player);
            if (data == null) {
                return false;
            }
            Object teleporter = rtpTeleporter.get(data);
            Object result = teleport.invoke(teleporter, player, positionSupplier(dim, pos));
            return Boolean.TRUE.equals(isSuccess.invoke(result));
        } catch (Throwable t) {
            warnOnce("teleportTo failed", t);
            return false;
        }
    }

    /** True when the player is in the overworld. False on any failure. */
    public static boolean isOverworld(ServerPlayer player) {
        try {
            return player.serverLevel().dimension().equals(Level.OVERWORLD);
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object playerData(ServerPlayer player) throws Exception {
        Optional<?> opt = (Optional<?>) getOrCreate.invoke(null, player);
        return opt == null ? null : opt.orElse(null);
    }

    private static void warnOnce(String message, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LinggangoRtp.LOGGER.warn("[rtp] {} ({})", message, t.toString());
        }
    }

    /**
     * Pure dimension/position holder used when no FTB {@code TeleportPos} can be
     * built. Package-visible to the test, which asserts the supplied coordinates
     * survive unchanged.
     */
    public record Target(ResourceKey<Level> dimension, BlockPos pos) {
    }
}
