# MemFix (`memfix`)

Server-side memory-leak sweeper for the Linggango modpack.
MC 1.20.1 / Forge 47.4.20 / Java 17.

## Build

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build --no-daemon --console=plain
# artifact: build/libs/memfix-1.1.0.jar
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

## /rtp history

`/rtp` is FTB Essentials' `TeleportCommands.findBlockPos`. For each of up to
`rtp.max_tries` candidates it calls `world.getChunkAt(pos)` and then
`world.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES, pos)`. Both need the chunk at
full status, so both block the calling thread while the chunk pipeline runs. On
the server thread that stalls every other player for the whole generation time.

MemFix **1.0.0** tried to fix this with two config-gated, reflective components:

1. `RtpGuard` registered a listener on `FTBEssentialsEvents.RTP_EVENT` and
   declined any candidate whose chunk was neither loaded nor on disk, so
   `/rtp` would skip instead of generating.
2. `ChunkPregen` generated the destination area once at startup, and
   `RegionIndex` read region-file headers to tell "on disk" from "generated".

This did not work out. The pregenerator covered only a small area in practice
(the log showed `pregen: 512-block area already generated, skipping`) while the
pack's FTB config sets `min_distance: 500` / `max_distance: 2048`, so the guard
declined nearly every candidate and `/rtp` almost always failed with "could not
find a location". **1.1.0 deleted all three components** and the `[memfix.rtp]`
config table; random teleport is now the separate `linggango-rtp` mod, which runs
the search off the server tick thread rather than refusing to search.

If you ever revisit this: the lesson is that declining ungenerated candidates
makes `/rtp` useless unless a pregenerator keeps up with `rtp.max_distance`, and
a background pregenerator of that size is far more expensive than doing the
search off-thread.
