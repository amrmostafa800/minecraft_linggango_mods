# Linggango RTP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new server-side Forge mod, `linggango-rtp`, that replaces FTB Essentials' synchronous `/rtp` location search with an EssentialsX-style asynchronous one that can reach terrain that has never been generated.

**Architecture:** The mod intercepts `/rtp` at Forge's `CommandEvent`, cancels FTB's synchronous implementation, and runs its own search: pick a uniformly-by-area random point within a configured radius, load that chunk off the main thread with `ServerChunkCache.getChunkFuture(...)`, validate the landing on the server thread when the chunk is ready, retry up to N times, then hand the found position to FTB's own `rtpTeleporter.teleport(player, positionGetter)` so FTB still applies cooldown, dimension blacklist, warm-up and teleport history. All FTB access is reflective, so the mod compiles against Forge alone like `linggango-memfix`.

**Tech Stack:** Java 17, Minecraft Forge 47.4.20 (MC 1.20.1), ForgeGradle `[6.0,6.2)`, JUnit 5, Mojang official mappings.

**Spec:** `docs/superpowers/specs/2026-10-10-linggango-rtp-design.md`

## Global Constraints

- MC 1.20.1, Forge 47.4.20, Java 17; build with ForgeGradle `[6.0,6.2)`, official mappings `1.20.1`, `org.gradle.jvmargs=-Xmx3G`, no Gradle daemon.
- Server-side only. No client install; the mod does nothing if FTB Essentials is absent.
- `/rtp` may land only in the **overworld**. In other dimensions the mod must not cancel the command; FTB's own behaviour stands.
- Search area is `minRadius` (default 0) to `maxRadius` (default 32000) from `centerX/centerZ` (default 0,0), clamped to the world border.
- **No main-thread terrain generation.** Chunk generation goes through `ServerChunkCache.getChunkFuture`; nothing blocks the server tick waiting on a chunk.
- Cooldowns are FTB's, unchanged: delegate the final teleport to FTB's `rtpTeleporter.teleport(...)`; never teleport a player directly.
- Reflection only; the mod declares no compile-time dependency on `ftbessentials`, `ftbchunks` or `ftbteams`.
- `mods.toml` declares `ftbessentials` as a soft (`mandatory=false`) dependency, no load-order directive.
- Package root: `com.linggango.rtp`. Artifact: `linggango-rtp-1.0.0.jar`.

## Review Focus

Unpinned inputs most likely to bite a user of this software, each pinned to a test in the task that owns the code:

1. **World border smaller than `maxRadius`** — a candidate outside the border must be clamped to inside it, never teleported outside. (Task 3)
2. **Candidate is an excluded biome** (ocean/river/deep dark/mushroom) — search must reject and retry, not land there. (Task 4)
3. **Player disconnects, dies, changes dimension, or the server stops mid-search** — no teleport after that; the pending search is cancelled. (Task 7)
4. **Cooldown is active** — the mod must let FTB emit the cooldown message and must not start a search. (Task 6)
5. **`/rtp` run in the Nether or End** — the mod must not cancel; FTB's path (including its End blacklist) runs untouched. (Task 6)

---

### Task 1: Scaffold the `linggango-rtp` project and confirm it loads

**Files:**
- Create: `linggango-rtp/build.gradle`, `linggango-rtp/gradle.properties`, `linggango-rtp/settings.gradle`, `linggango-rtp/gradlew`, `linggango-rtp/gradlew.bat`, `linggango-rtp/gradle/wrapper/*`
- Create: `linggango-rtp/src/main/resources/META-INF/mods.toml`, `linggango-rtp/src/main/resources/pack.mcmeta`
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/LinggangoRtp.java`
- Create: `linggango-rtp/README.md`, `linggango-rtp/AGENTS.md`, `linggango-rtp/.gitignore`, `linggango-rtp/.gitattributes`
- (Reuse the Gradle wrapper and `.gitignore`/`.gitattributes` from `../linggango-memfix/`.)

**Interfaces:**
- Produces: mod id `linggango_rtp`; main class `com.linggango.rtp.LinggangoRtp` with a `@Mod("linggango_rtp")` no-arg constructor; `LinggangoRtp.MODID = "linggango_rtp"`, `LinggangoRtp.LOGGER`.

- [ ] **Step 1: Copy and rename the build files**

Copy `../linggango-memfix/{build.gradle,gradle.properties,settings.gradle,gradlew,gradlew.bat,gradle/,.gitignore,.gitattributes}` into `linggango-rtp/`. In `gradle.properties` set `mod_id=linggango_rtp`, `mod_name=Linggango RTP`, `mod_group_id=com.linggango.rtp`, `mod_version=1.0.0`, `mod_description=Asynchronous /rtp location search that reaches ungenerated terrain without stalling the server thread.` In `settings.gradle` set `rootProject.name = 'linggango_rtp'`. Leave `build.gradle` otherwise unchanged (it has no mod dependencies, which is exactly what we want).

- [ ] **Step 2: Write `mods.toml` and `pack.mcmeta`**

Copy memfix's `META-INF/mods.toml` and `pack.mcmeta`, change the description/display name via the `expand` properties already wired, and add one soft dependency block:

```toml
[[dependencies.linggango_rtp]]
    modId = "ftbessentials"
    mandatory = false
    versionRange = "[2001.2,)"
    ordering = "NONE"
    side = "BOTH"
```

- [ ] **Step 3: Write the minimal mod class**

```java
package com.linggango.rtp;

import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LinggangoRtp.MODID)
public class LinggangoRtp {
    public static final String MODID = "linggango_rtp";
    public static final Logger LOGGER = LoggerFactory.getLogger("linggango-rtp");

    public LinggangoRtp() {
        LOGGER.info("[rtp] loaded");
    }
}
```

- [ ] **Step 4: Build**

Run: `cd linggango-rtp && ./gradlew clean build --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`; `build/libs/linggango_rtp-1.0.0.jar` exists.

- [ ] **Step 5: Confirm it loads on the local pack**

Copy the jar to `/workspace/project/modpack/server/mods/`, run the server one boot, and confirm `[rtp] loaded` in the log. Remove the jar afterwards.

- [ ] **Step 6: Commit**

```bash
git add linggango-rtp
git commit -m "feat(rtp): scaffold linggango-rtp mod"
```

---

### Task 2: FTB reflection access

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/FtbAccess.java`
- Test: `linggango-rtp/src/test/java/com/linggango/rtp/FtbAccessTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `FtbAccess.available() : boolean` — true when `dev.ftb.mods.ftbessentials.FTBEssentialsEvents` and `FTBEPlayerData` resolve.
  - `FtbAccess.checkCooldown(ServerPlayer) : long` — remaining cooldown millis, `0` if none or if FTB is absent.
  - `FtbAccess.teleportTo(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) : boolean` — delegates to FTB's `rtpTeleporter.teleport(player, p -> new TeleportPos(dim, pos))`; true if the result `isSuccess()`.
  - `FtbAccess.isOverworld(ServerPlayer) : boolean`.

- [ ] **Step 1: Write the failing test (pure helper)**

The only pure part is the position-supplier identity: build a `TeleportPos`-equivalent resource key + pos and assert the object returned by the supplier equals what was passed. Expose `FtbAccess.positionSupplier(ResourceKey<Level>, BlockPos)` returning a `Function<Object,Object>` and test that it ignores its argument and returns a holder with the same dim/pos.

```java
@Test
void supplierIgnoresArgument() { /* assert returned holder dim/pos match */ }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*FtbAccessTest*' --no-daemon`
Expected: FAIL — class not found.

- [ ] **Step 3: Implement `FtbAccess`**

Resolve once, in a static initializer, guarded so failure logs a warning and leaves the mod inert: `FTBEPlayerData.getOrCreate(Player)` → `Optional`; `FTBEPlayerData.rtpTeleporter` field; `WarmupCooldownTeleporter.checkCooldown()`; `WarmupCooldownTeleporter.teleport(ServerPlayer, Function)`; `TeleportPos(ResourceKey<Level>, BlockPos)` constructor; and `TeleportPos.TeleportResult.isSuccess()`. All lookups wrapped in try/catch that set `available=false` on failure. `teleportTo` reflects `teleport(...)` and reflects `isSuccess()` on the result.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests '*FtbAccessTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): reflective FTB Essentials access"
```

---

### Task 3: Candidate point picker

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/CandidatePicker.java`
- Test: `linggango-rtp/src/test/java/com/linggango/rtp/CandidatePickerTest.java`

**Interfaces:**
- Produces: `CandidatePicker.pick(Random rng, int centerX, int centerZ, int minRadius, int maxRadius, double borderHalfSize) : int[]{x, z}`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void uniformByAreaWithinBounds()   // over 10k draws, all radii in [min,max], and ~half fall beyond 0.707*max (area-uniform, not radius-uniform)
@Test void clampsToBorder()              // borderHalfSize=1000, maxRadius=32000 -> every draw |x|,|z| <= 1000
@Test void minRadiusRespected()          // minRadius=500 -> no draw with radius < 500-1
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*CandidatePickerTest*' --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Implement `pick`**

`r = sqrt(uniform(minR^2, maxR^2))`, `theta = uniform(0, 2*pi)`, `x = centerX + round(cos(theta)*r)`, `z = centerZ + round(sin(theta)*r)`; clamp each of x,z to `±borderHalfSize` when the border is finite (`borderHalfSize` `<= 0` means "infinite", skip clamping). Use a `Random` passed in for testability, not `ThreadLocalRandom`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*CandidatePickerTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): uniform-by-area candidate picker with border clamp"
```

---

### Task 4: Landing validator

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/LandingValidator.java`
- Test: `linggango-rtp/src/test/java/com/linggango/rtp/LandingValidatorTest.java`

**Interfaces:**
- Consumes: `FtbAccess` (Task 2) for the claim check.
- Produces:
  - `LandingValidator.biomeExcluded(ResourceLocation biomeId) : boolean` (pure; uses the configured exclusion set)
  - `LandingValidator.findSafeY(ServerLevel level, BlockPos column) : OptionalInt` (server-thread; scans the column for a safe landing)
  - `LandingValidator.claimedByOtherTeam(ServerPlayer player, ServerLevel level, BlockPos pos) : boolean` (reflective FTB Chunks check; `false` if the API is absent)

- [ ] **Step 1: Write the failing tests (pure biome set)**

```java
@Test void excludesOceansAndRivers()      // "minecraft:ocean", "minecraft:river" -> true
@Test void excludesDeepDarkAndMushroom()  // "minecraft:deep_dark", "minecraft:mushroom_fields" -> true
@Test void allowsNormalBiome()            // "minecraft:plains" -> false
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*LandingValidatorTest*' --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Implement the validator**

`biomeExcluded` lowercases and looks up in the configured set. `findSafeY` uses `level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column)`, requires the surface block to be solid (`BlockState.isSolid()`), the two blocks above to be `isAir()`, and the surface not to be lava/water/fire; returns empty if none. `claimedByOtherTeam` reflectively calls FTB Chunks' `FTBChunksAPI.api().getManager().getChunk(ChunkDimPos)` and, when the chunk has team data, checks `isTeamMember`/`isAlly` for the querying player; any reflection failure returns `false` (skip).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*LandingValidatorTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): landing validation (biome, safe Y, foreign claims)"
```

---

### Task 5: Config

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/RtpConfig.java`
- Modify: `linggango-rtp/src/main/java/com/linggango/rtp/LinggangoRtp.java` (register the config)

**Interfaces:**
- Produces: `RtpConfig.SPEC`; getters `enabled()`, `centerX()`, `centerZ()`, `minRadius()`, `maxRadius()`, `findAttempts()`, `respectClaims()`, `excludedBiomes()` (`Set<String>`). Safe defaults returned on any `Throwable`, matching memfix's pattern.

- [ ] **Step 1: Implement `RtpConfig`**

Forge config `[rtp]` with the keys and defaults from the spec: `enabled=true`, `centerX=0`, `centerZ=0`, `minRadius=0` (range 0..30_000_000), `maxRadius=32000` (range 0..30_000_000), `findAttempts=10` (1..1000), `respectClaims=true`, `excludedBiomes` = the expanded default list. Register with `ModLoadingContext` in the constructor. No unit test (config values are read back from disk, not logic).

- [ ] **Step 2: Build and boot**

Run: `./gradlew build --no-daemon && cp build/libs/linggango_rtp-1.0.0.jar /workspace/project/modpack/server/mods/` then boot the server and confirm `config/linggango-rtp-common.toml` is written with `maxRadius = 32000`. Remove the jar afterwards.

- [ ] **Step 3: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): config with 32k radius and expanded biome exclusions"
```

---

### Task 6: Command interception

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/RtpCommandHook.java`
- Modify: `linggango-rtp/src/main/java/com/linggango/rtp/LinggangoRtp.java` (register the event handler)
- Test: `linggango-rtp/src/test/java/com/linggango/rtp/RtpCommandHookTest.java`

**Interfaces:**
- Consumes: `FtbAccess` (Task 2), `RtpConfig` (Task 5), `RtpSearch` (Task 7 — forward reference; the hook calls `RtpSearch.start(ServerPlayer)`).
- Produces:
  - `RtpCommandHook.shouldIntercept(String rootCommand, String dimensionId, boolean onCooldown, boolean ftbAvailable, boolean enabled) : boolean` (pure decision, testable)
  - `RtpCommandHook.onCommand(CommandEvent)` — registered at `EventPriority.HIGHEST` on `MinecraftForge.EVENT_BUS`.

- [ ] **Step 1: Write the failing tests (pure decision)**

```java
@Test void interceptsOverworldRtp()          // ("rtp","minecraft:overworld",false,true,true) -> true
@Test void ignoresNetherAndEnd()             // dimension != overworld -> false
@Test void ignoresWhenOnCooldown()           // onCooldown=true -> false (let FTB message)
@Test void ignoresWhenFtbAbsentOrDisabled()  // ftbAvailable=false or enabled=false -> false
@Test void ignoresOtherCommands()            // rootCommand="home" -> false
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests '*RtpCommandHookTest*' --no-daemon`
Expected: FAIL.

- [ ] **Step 3: Implement the hook**

`onCommand` reads the root command node (`context.getNodes().get(0).getNode().getName()`), the source's player, its dimension id, `FtbAccess.available()`, `RtpConfig`, and `FtbAccess.checkCooldown(player) > 0`. If `shouldIntercept` is true: `event.setCanceled(true)` and `RtpSearch.start(player)`. Otherwise return without cancelling. Wrap all reflection in try/catch; on any failure do not cancel.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests '*RtpCommandHookTest*' --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): intercept /rtp in the overworld only"
```

---

### Task 7: Asynchronous search

**Files:**
- Create: `linggango-rtp/src/main/java/com/linggango/rtp/RtpSearch.java`
- Modify: `linggango-rtp/src/main/java/com/linggango/rtp/LinggangoRtp.java` (register lifecycle events)

**Interfaces:**
- Consumes: `CandidatePicker` (3), `LandingValidator` (4), `RtpConfig` (5), `FtbAccess` (2).
- Produces:
  - `RtpSearch.start(ServerPlayer player)` — refuses if a search is pending for that player; otherwise begins.
  - `RtpSearch.cancel(UUID playerId)` and `RtpSearch.cancelAll()`.
  - `RtpSearch.pendingCount() : int` (for the lifecycle test).

- [ ] **Step 1: Implement `start`**

Maintain `Map<UUID, Search>` of in-flight searches. If the player has one, message "already searching" and return. Otherwise create a `Search` that, per attempt: `CandidatePicker.pick(...)` using the overworld border half-size from `level.getWorldBorder().getSize()/2`; derive the chunk; call `level.getChunkSource().getChunkFuture(chunkX, chunkZ, ChunkStatus.FULL, true)`; in its callback (server thread) run `LandingValidator.findSafeY` and `claimedByOtherTeam`, and on success call `FtbAccess.teleportTo(player, overworldKey, safePos)` and message the player "Found a location after N attempts"; else if attempts remain, recurse; else message "Could not find a valid location" and drop the entry. Store the `Search` so it can be cancelled. All callbacks begin by checking the search is still current and the player is still on the same level.

- [ ] **Step 2: Wire lifecycle cancellation**

In `LinggangoRtp`, on `PlayerEvent.PlayerLoggedOutEvent`, `PlayerEvent.PlayerChangedDimensionEvent`, `LivingDeathEvent` (player) and `ServerStoppedEvent`, call `RtpSearch.cancel(player)` / `cancelAll()`. Register these on `MinecraftForge.EVENT_BUS`.

- [ ] **Step 3: Test the lifecycle rule (pure registry)**

Expose the pending map behind `pendingCount()` and add package-private `RtpSearch.markPending(UUID)` / `finish(UUID)` seams, then:

```java
@Test void secondStartIsRefused()  // markPending(u); isPending(u) == true
@Test void cancelClearsPending()   // markPending(u); cancel(u); pendingCount()==0
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --no-daemon`
Expected: PASS (Tasks 2-7 tests all green).

- [ ] **Step 5: Build**

Run: `./gradlew clean build --no-daemon --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add linggango-rtp/src
git commit -m "feat(rtp): async search with single-flight and lifecycle cancel"
```

---

### Task 8: End-to-end on the local pack, then deploy

**Files:**
- Modify: `linggango-rtp/README.md`, `linggango-rtp/AGENTS.md` (document the mod, the retirement of the memfix guard, the `/rtp` event path, and the verification results).

- [ ] **Step 1: Local end-to-end with a client**

Start the local server with `linggango-rtp` installed. Connect the instrumented client bot. Run `/rtp` several times and confirm, from the server log and the bot's received position, that the player lands in terrain outside the previously generated area and that no log line reports a long tick (`ServerHangWatchdog`). Confirm the cooldown message still appears on a rapid second `/rtp`.

- [ ] **Step 2: Verify FTB delegation**

Confirm in the log that `/rtp` still adds a `/back` entry (FTB's `addTeleportHistory` ran, i.e. our delegation worked) and that `difficulty_enhancement_ftbe`'s cooldown still gates repeat use.

- [ ] **Step 3: Deploy over SFTP**

Upload `build/libs/linggango_rtp-1.0.0.jar` to the server's `mods/`, and set `config/memfix-common.toml` `[memfix.rtp] guard = false` and `pregenerate = false` (back up the original first). Restart and confirm `[rtp] loaded`, the memfix guard not installed as active, and a live `/rtp` reaching new terrain.

- [ ] **Step 4: Update docs and commit**

Document behaviour, config, the memfix-guard retirement, and the verification output in `README.md`/`AGENTS.md`.

```bash
git add linggango-rtp
git commit -m "docs(rtp): end-to-end verification and deployment notes"
```

---

## Self-Review

- **Spec coverage:** interception (T6), picker (T3), async load (T7), validation (T4), retry (T7), teleport+cooldown delegation (T2/T7), config (T5), overworld-only (T6), expanded biomes (T4/T5), foreign-claim avoidance (T4), reflection-only (T2), lifecycle (T7), deploy + memfix retirement (T8). Covered.
- **Type consistency:** `RtpSearch.start(ServerPlayer)` used by T6 and defined by T7; `FtbAccess.checkCooldown`/`teleportTo` used by T6/T7 and defined by T2; `CandidatePicker.pick` signature fixed in T3 and called in T7. Consistent.
- **Review Focus:** each of the five lines has a test in T3/T4/T6/T7.
