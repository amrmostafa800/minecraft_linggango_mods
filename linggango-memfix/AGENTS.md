# MemFix (`memfix`)

Server-side memory-leak sweeper for the Linggango modpack.
MC 1.20.1 / Forge 47.4.20 / Java 17.

## Build

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build --no-daemon --console=plain
# artifact: build/libs/memfix-1.0.0.jar
```

ForgeGradle's `FMLJavaModLoadingContext.get()` deprecation warning is expected and benign.

The first build downloads and decompiles Minecraft through ForgeGradle and takes a few minutes.
Later builds are seconds.

## Verifying the targets

```bash
./tools/verify-targets.py /path/to/serverpack    # default /workspace/pack
```

Asserts all 36 configured targets are static fields in the shipped pack. Run this after any
modpack update; it reports which targets drifted. It is a script and not a Gradle task on purpose:
it reads the pack's SRG-named jars, which the Mojang-mapped mod sources cannot compile against, and
it must not link the classes it inspects (their static initializers need the Minecraft bootstrap).

## Invariants to preserve

1. **Never drop a key you cannot prove is unreachable.** `Sweeper.isStale` returns `false` for any
   type it does not recognise. Loosening this to "drop anything old" would delete live saved data —
   e.g. a player's home or progress — and the failure would be silent and delayed.
2. **A never-joined player's UUID is not stale.** `isOfflinePlayer` requires the id to have been
   *seen online* and then to be absent. Dropping on "absent" alone would erase data for every
   player who has not logged in since the last restart.
3. **A target that will not resolve must be skipped, not fatal.** `Target.ensure` swallows
   `ClassNotFoundException`/`NoSuchFieldException` and marks the target broken. This is what lets
   the mod survive modpack updates and load in packs without the mods it repairs. Removing the
   `broken` short-circuit makes every sweep re-attempt a `Class.forName` that will never succeed.
4. **Item-stack keyed maps are bounded, never cleared.** `evictOldest` is a heuristic: `HashMap`
   iteration order is not insertion order, so it bounds growth without knowing entry ages. That is
   acceptable because these are caches. Do not extend the same eviction to UUID or level maps —
   those hold state, and losing an entry there is data loss.
5. **Config is read after `ModConfigEvent`, not in the constructor.** Forge has not loaded the
   file while mods are constructing, so the defaults are all that is visible then. Rebuilding
   targets only in the constructor silently ignores the user's edited `targets` list.
6. **Game events go on `MinecraftForge.EVENT_BUS`, mod events on the mod bus.** `ServerTickEvent`
   and `LevelEvent.Unload` are game events; `ModConfigEvent` is a mod-bus event. Registering the
   wrong one means the handler never fires, with no error.

## Gotchas

- The reobf step is what makes the jar load in the pack. `Class.forName` on SRG-named pack classes
  and reflective `Field.get` work regardless of mapping, but the *direct* calls to `MinecraftServer`
  and friends are compiled against Mojang mappings and must be reobfuscated to SRG. If you build
  with plain `javac` against the pack's jars instead, the source must be SRG-named — that is how the
  first iteration of this mod was built, and the two styles are not interchangeable.
- `LevelEvent.Unload` fires per-dimension and passes `null` to the sweeper deliberately: only
  in-memory entity/level staleness should be evaluated there, and the unloaded level has already
  left the server's level list by then.
- `-XX:+ExitOnOutOfMemoryError` is in the pack's JVM args on purpose. A clean crash-restart beats
  a server that thrashes and stalls every player.
- The pack's `resourceful-config-web.json` holds a generated web password. It is a private-server
  credential; do not copy it into this repo or any public artefact.

## /rtp freezing the server thread

`/rtp` is FTB Essentials' `TeleportCommands.findBlockPos`. For each of up to
`rtp.max_tries` candidates it calls `world.getChunkAt(pos)` and then
`world.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES, pos)`. Both need the chunk at
full status, so both block the calling thread while the chunk pipeline runs. On
the server thread that stalls every other player for the whole generation time.

Candidates are picked at a random angle and a radius between `rtp.min_distance`
and `rtp.max_distance` from the **world origin**, not from the player. With the
pack's original `max_distance: 25000` and `max_tries: 100` that is up to 100
stalls per command, each potentially in never-generated terrain. `view-distance`
and the chunk optimisers do not help: the work is unavoidable, only its duration
changes, so a faster CPU shortens each stall but never removes it.

### The fix

Two parts, both config-gated and reflective so packs without FTB Essentials are
unaffected:

1. **`RtpGuard`** registers a listener on `FTBEssentialsEvents.RTP_EVENT`. FTB
   fires the event *before* both blocking calls and skips the candidate when a
   listener returns `false`. The guard declines any candidate whose chunk is
   neither loaded (`getChunkNow`) nor present on disk (`RegionIndex`). The loop
   then moves to another random point. It never returns `true`, so it cannot
   override another mod's decision to accept a candidate.
2. **`ChunkPregen`** generates the destination area once at startup, a few
   chunks per tick with a non-expiring ticket, and writes `memfix-pregen.txt`.
   Without this, declining alone would make `/rtp` report "could not find a
   valid location" in fresh terrain.

`RegionIndex` reads the region file header to decide whether a chunk is on disk,
so the guard never loads a chunk to make its decision. Chunks merely on disk are
fine: loading one is off-thread work, unlike generating it.

### Tuning

`config/memfix-common.toml` `[memfix.rtp]`: `radiusBlocks` must be **at least**
FTB's `rtp.max_distance`, and both should be centred on the origin
(`centerX`/`centerZ` = 0), because that is what `/rtp` measures from. The pack
uses `radiusBlocks = 512` with `min_distance: 500` / `max_distance: 512`, so the
pregenerated annulus is exactly the area `/rtp` can pick from and the guard
always finds a generated candidate.

### addRegionTicket takes a distance, not a level

`ServerChunkCache.addRegionTicket(type, pos, distance, value)` builds its ticket
as `ChunkLevel.byStatus(FULL) - distance`. Passing a chunk level there is wrong:
`ChunkLevel.byStatus(FULL)` is 33, so a "level" of 33 resolves to ticket level 0,
which force-loads the chunk *and* propagates a load demand roughly 33 chunks in
every direction -- thousands of chunks per ticket. That wedged the server tick
and produced a `ServerHangWatchdog ... single server tick took 180.00 seconds`
crash. Pass `0` for "exactly FULL with no margin".

### Verifying the guard without a client

`/rtp` refuses to run from the console ("A player is required"). To exercise the
real event path, temporarily add a class that does
`FTBEssentialsEvents.RTP_EVENT.invoker().teleport(level, null, pos, 0)` for
known-generated and known-ungenerated positions and logs `EventResult.isFalse()`
for each. Verified: in-area positions accepted, far-away positions DECLINED.
Remove the probe before packaging.
