package com.linggango.memfix;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Stops FTB Essentials' {@code /rtp} from generating terrain on the server
 * thread, which is what freezes the server when the command runs.
 *
 * <p>{@code TeleportCommands.findBlockPos} picks up to {@code rtp.max_tries}
 * random points at a radius between {@code min_distance} and
 * {@code max_distance} from the origin and, for each one, calls
 * {@code world.getChunkAt(pos)} followed by
 * {@code world.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES, pos)}. Both require
 * the chunk at full status, so both block the calling thread while the chunk
 * pipeline runs; on the server thread that stalls the whole server for as long
 * as generation takes. Because {@code /rtp} reaches thousands of blocks out,
 * the candidates are unloaded and often never-generated terrain, so one command
 * can stall the server once per attempt. {@code view-distance} and the chunk
 * optimisers do not help: the work is unavoidable, only its duration changes. A
 * faster CPU shortens each stall but never removes it.
 *
 * <p>FTB fires {@code FTBEssentialsEvents.RTP_EVENT} for every candidate and
 * skips both of those calls when a listener returns {@code false}. Declining
 * candidates whose chunk has not been generated yet therefore removes the stall
 * entirely: the loop simply moves on to another random point, and finds a
 * generated one immediately when the destination area has been pregenerated.
 * The chunk may be on disk and unloaded - that is enough, because loading it is
 * off-thread work rather than generation.
 *
 * <p>Everything here is reflective. FTB Essentials and Architectury are looked
 * up by name and the listener is a dynamic proxy, so this mod still compiles
 * and loads in packs that ship neither, and a missing or renamed API disables
 * the guard with a log line instead of breaking startup.
 */
final class RtpGuard implements InvocationHandler {

    private static final String EVENTS_CLASS = "dev.ftb.mods.ftbessentials.FTBEssentialsEvents";
    private static final String LISTENER_CLASS = "dev.ftb.mods.ftbessentials.FTBEssentialsEvents$RTP";
    private static final String RESULT_CLASS = "dev.architectury.event.EventResult";

    private static Object passResult;
    private static Object falseResult;

    /** Candidates declined this session, so the operator can tune the radius. */
    private static volatile int declined;

    private RtpGuard() {
    }

    /** Wires the listener up, or logs why it could not be. Returns success. */
    static boolean install() {
        try {
            Class<?> events = Class.forName(EVENTS_CLASS);
            Class<?> listenerType = Class.forName(LISTENER_CLASS);
            Class<?> resultType = Class.forName(RESULT_CLASS);

            // Resolved once here rather than per candidate: /rtp can fire the
            // event a hundred times in a single command.
            passResult = resultType.getMethod("pass").invoke(null);
            falseResult = resultType.getMethod("interruptFalse").invoke(null);

            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[] { listenerType }, new RtpGuard());

            Object event = events.getField("RTP_EVENT").get(null);
            // Resolve register() from the public Event interface. The concrete
            // EventImpl is package-private, so calling it through its own class
            // trips the module system and fails at runtime.
            Class<?> eventType = Class.forName("dev.architectury.event.Event");
            eventType.getMethod("register", Object.class).invoke(event, listener);
            return true;
        } catch (Throwable t) {
            MemFix.LOGGER.warn("[memfix] /rtp guard unavailable ({}); leaving /rtp untouched",
                    t.toString());
            return false;
        }
    }

    static int declinedCount() {
        return declined;
    }

    static void resetCount() {
        declined = 0;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "memfix-rtp-guard";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }
        try {
            return evaluate((ServerLevel) args[0], (BlockPos) args[2]);
        } catch (Throwable t) {
            // A guard fault must not turn into a failed /rtp: pass the candidate
            // through and let FTB do what it would have done anyway.
            return passResult;
        }
    }

    /**
     * {@code false} when the candidate must not be used, otherwise
     * {@code pass()} to continue to the next listener. {@code true} is never
     * returned: this guard only ever rejects candidates, so it cannot override
     * another mod's decision to accept one.
     */
    private Object evaluate(ServerLevel level, BlockPos pos) {
        if (!MemFixConfig.rtpGuardEnabled() || passResult == null) {
            return passResult;
        }
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null) {
            return passResult;
        }
        if (RegionIndex.chunkExists(RegionIndex.regionDir(level), pos.getX() >> 4, pos.getZ() >> 4)) {
            return passResult;
        }
        declined++;
        return falseResult;
    }
}
