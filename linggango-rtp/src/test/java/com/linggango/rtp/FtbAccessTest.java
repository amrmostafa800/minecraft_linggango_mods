package com.linggango.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Covers only the pure part of {@link FtbAccess}: the position supplier. FTB
 * Essentials is not on the test classpath, so every reflective lookup fails and
 * {@link FtbAccess#available()} is false; the supplier falls back to the plain
 * {@link FtbAccess.Target} holder, which is exactly the path a pack without FTB
 * takes. The reflective FTB calls need a live Minecraft bootstrap and are not
 * exercised here.
 */
class FtbAccessTest {

    @Test
    void unavailableWhenFtbAbsent() {
        assertFalse(FtbAccess.available(),
                "FTB Essentials is not on the test classpath, so the mod must stay inert");
    }

    @Test
    void rtpDisabledWhenFtbAbsent() {
        assertFalse(FtbAccess.rtpEnabled(),
                "with FTB absent the RTP gate must resolve false so interception never fires");
    }

    @Test
    void cooldownFailsClosedWhenFtbAbsent() {
        // FTB is absent, so checkCooldown short-circuits before touching the
        // player. It must report "on cooldown" (true) so the command falls
        // through to FTB's native path: a broken cooldown call can never
        // disable the gate. The player argument is therefore never dereferenced.
        assertTrue(FtbAccess.checkCooldown(null),
                "an unresolvable cooldown check must fail closed (treat as on cooldown)");
    }

    @Test
    void supplierIgnoresArgument() throws Exception {
        ResourceKey<Level> dim = overworldKey();
        BlockPos pos = new BlockPos(1234, 64, -5678);

        Function<Object, Object> supplier = FtbAccess.positionSupplier(dim, pos);

        Object fromNull = supplier.apply(null);
        Object fromOther = supplier.apply("not-a-player");

        assertTarget(dim, pos, fromNull);
        assertTarget(dim, pos, fromOther);
        assertEquals(fromNull, fromOther, "the supplier must ignore its argument");
    }

    @Test
    void supplierKeepsExactCoordinates() throws Exception {
        ResourceKey<Level> dim = overworldKey();
        BlockPos pos = new BlockPos(-1, 0, 1);

        FtbAccess.Target target = (FtbAccess.Target) FtbAccess.positionSupplier(dim, pos).apply("ignored");

        assertEquals(dim, target.dimension());
        assertEquals(pos, target.pos());
        assertEquals(-1, target.pos().getX());
        assertEquals(0, target.pos().getY());
        assertEquals(1, target.pos().getZ());
    }

    /**
     * A real {@code minecraft:overworld} dimension key, built through the same
     * pure factory the SRG bootstrap uses. The public {@code ResourceKey.create}
     * routes through {@code BuiltInRegistries}, whose {@code MappedRegistry}
     * constructor demands {@code Bootstrap.bootStrap()} — which cannot run under
     * plain JUnit on Forge. The private {@code create(ResourceLocation,
     * ResourceLocation)} overload touches no registries.
     */
    @SuppressWarnings("unchecked")
    private static ResourceKey<Level> overworldKey() throws Exception {
        Method create = ResourceKey.class.getDeclaredMethod(
                "create", ResourceLocation.class, ResourceLocation.class);
        create.setAccessible(true);
        return (ResourceKey<Level>) create.invoke(null,
                ResourceLocation.tryBuild("minecraft", "dimension"),
                ResourceLocation.tryBuild("minecraft", "overworld"));
    }

    private static void assertTarget(ResourceKey<Level> dim, BlockPos pos, Object holder) {
        assertNotNull(holder);
        assertTrue(holder instanceof FtbAccess.Target,
                "the supplier must return the pure holder");
        FtbAccess.Target target = (FtbAccess.Target) holder;
        assertEquals(dim, target.dimension());
        assertEquals(pos, target.pos());
    }
}
