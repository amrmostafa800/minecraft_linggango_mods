package com.linggango.rtp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Decides whether a candidate is a usable landing: the biome may not be
 * excluded, the ground must be solid with two blocks of headroom above it and
 * no fluid, and the chunk may not be claimed by a team the player has no
 * standing in.
 *
 * <p>{@link #biomeExcluded} is pure and unit-tested. {@link #findSafeY} and
 * {@link #claimedByOtherTeam} touch the live world (and, for the latter, FTB
 * Chunks), so they are deliberately small and total: both catch every failure
 * and fall back to a safe result, and neither is exercised by JUnit without a
 * Minecraft bootstrap.
 */
public final class LandingValidator {

    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private static volatile boolean ftbAvailable;
    private static Method api;
    private static Method getManager;
    private static Constructor<?> chunkDimPosCtor;
    private static Method getChunk;
    private static Method getTeamData;
    private static Method isTeamMember;
    private static Method isAlly;

    static {
        try {
            Class<?> chunksApi = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
            api = chunksApi.getMethod("api");

            Class<?> apiType = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API");
            getManager = apiType.getMethod("getManager");

            Class<?> managerType = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunkManager");
            Class<?> chunkDimPos = Class.forName("dev.ftb.mods.ftblibrary.math.ChunkDimPos");
            chunkDimPosCtor = chunkDimPos.getConstructor(
                    Class.forName("net.minecraft.world.level.Level"),
                    Class.forName("net.minecraft.core.BlockPos"));
            getChunk = managerType.getMethod("getChunk", chunkDimPos);

            Class<?> claimedChunk = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunk");
            getTeamData = claimedChunk.getMethod("getTeamData");

            Class<?> chunkTeamData = Class.forName("dev.ftb.mods.ftbchunks.api.ChunkTeamData");
            isTeamMember = chunkTeamData.getMethod("isTeamMember", UUID.class);
            isAlly = chunkTeamData.getMethod("isAlly", UUID.class);

            ftbAvailable = true;
        } catch (Throwable t) {
            warnOnce("FTB Chunks not usable; claim checks are skipped", t);
            ftbAvailable = false;
        }
    }

    private LandingValidator() {
    }

    /**
     * True when {@code biomeId} is in {@link RtpConfig#excludedBiomes()}. The
     * lookup is case-insensitive: the configured set is lowercased and so is the
     * query. A null {@code biomeId} is not excluded (this method is pure and
     * must never throw).
     */
    public static boolean biomeExcluded(ResourceLocation biomeId) {
        if (biomeId == null) {
            return false;
        }
        return RtpConfig.excludedBiomes().contains(biomeId.toString().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * The Y a player would stand at on this column, or empty when the column has
     * no safe footing. The surface is taken from the
     * {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} heightmap, then required
     * to be solid ground with two air blocks above and no fluid, lava, water or
     * fire at the surface. Total: any failure yields empty.
     */
    public static OptionalInt findSafeY(ServerLevel level, BlockPos column) {
        try {
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            BlockState state = level.getBlockState(surface);

            if (!state.isSolid()) {
                return OptionalInt.empty();
            }
            if (state.is(Blocks.LAVA) || state.is(Blocks.WATER) || state.is(Blocks.FIRE)
                    || !state.getFluidState().isEmpty()) {
                return OptionalInt.empty();
            }
            if (!level.getBlockState(surface.above()).isAir()
                    || !level.getBlockState(surface.above(2)).isAir()) {
                return OptionalInt.empty();
            }

            return OptionalInt.of(surface.getY() + 1);
        } catch (Throwable t) {
            warnOnce("findSafeY failed; treating the column as unsafe", t);
            return OptionalInt.empty();
        }
    }

    /**
     * True only when the chunk containing {@code pos} is claimed by a team that
     * {@code player} is neither a member nor an ally of. Returns false when
     * {@link RtpConfig#respectClaims()} is off, when FTB Chunks is absent, or on
     * any reflective failure: a skipped check must never reject a valid landing.
     *
     * <p>FTB Chunks is reached purely by reflection (see the static initializer);
     * the mod keeps no compile-time dependency on it.
     */
    public static boolean claimedByOtherTeam(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!RtpConfig.respectClaims() || !ftbAvailable) {
            return false;
        }
        try {
            Object apiInstance = api.invoke(null);
            Object manager = getManager.invoke(apiInstance);
            if (manager == null) {
                return false;
            }

            Object chunk = getChunk.invoke(manager, chunkDimPosCtor.newInstance(level, pos));
            if (chunk == null) {
                return false;
            }

            Object teamData = getTeamData.invoke(chunk);
            if (teamData == null) {
                return false;
            }

            UUID playerId = player.getUUID();
            boolean member = Boolean.TRUE.equals(isTeamMember.invoke(teamData, playerId));
            boolean ally = Boolean.TRUE.equals(isAlly.invoke(teamData, playerId));
            return !member && !ally;
        } catch (Throwable t) {
            warnOnce("claimedByOtherTeam failed; claim check skipped", t);
            return false;
        }
    }

    private static void warnOnce(String message, Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            LinggangoRtp.LOGGER.warn("[rtp] {} ({})", message, t.toString());
        }
    }
}
