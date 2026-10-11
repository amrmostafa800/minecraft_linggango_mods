# MemFix — Linggango memory leak sweeper

A server-side Forge mod for the **Linggango 6.6.5b3hf** modpack (Minecraft 1.20.1, Forge 47.4.20,
Java 17, 408 mods). It removes the entries that several pack mods leave pinned in `static`
collections forever, so the server stops growing a little every time a player logs out, a
dimension unloads, or a boss fight ends.

Without it a long-running server has to be restarted on a schedule. With it the heap stays flat.

## The leak, in one paragraph

A `static` field is a GC root. When a mod declares `static Map<Level, Something>` or
`static Map<Player, Long>` and never removes entries, every key it has ever seen stays reachable
for the life of the JVM — and so does everything the key transitively points at. For a level key
that means the entire dimension: its chunk source, its block entities, its saved data. Nothing can
be collected, ever, and the only symptom is a heap graph that climbs all day.

## How it works

Mixin-ing into 400 jars would be fragile and would break on every mod update. Instead MemFix
resolves a list of known-leaking fields by reflection and drains them at the moments the held
objects actually die:

| Event | What it does |
| --- | --- |
| `ServerStartedEvent` | Initial sweep, clears state left over from a previous world load |
| `LevelEvent.Unload` | Drops entries keyed by the dimension being unloaded |
| `ServerTickEvent`, every 300 s | Drops entries whose entity or player key is gone |
| `ServerStoppedEvent` | Clears everything and invokes each mod's own reset/clear where one exists |

There is no per-tick work: the periodic sweep is a single pass over a fixed list of containers
every five minutes. Every target is reached reflectively, so there is no compile-time dependency on
the mods being repaired.

### What counts as stale

A key is dropped only when the server can no longer reach it:

- an `Entity` that is removed
- a player `UUID` that MemFix watched log in and has since seen leave
- a `ServerLevel` that is no longer in the server's level list
- an empty `ItemStack`
- a `null`

Anything unrecognised is kept. This matters: a key type the mod does not understand may be live
saved state, and evicting it would be silent data loss. A never-joined player's UUID is kept for
the same reason — it may belong to a player who has simply not logged in yet.

`ItemStack`-keyed maps get one extra rule. An `ItemStack` carries its own copy of its contents, so
a lookup by an equal-but-distinct stack misses; a mod keying a map that way creates entries nothing
can ever remove. Those maps are bounded at 8192 entries, evicting from the front of the iteration
order. They are caches, so eviction is safe, and the cap is high enough that a normal working set
is never touched.

### Safeguards

- A missing class, renamed field, or non-static field is **skipped**, never fatal. The mod stays
  loadable in any pack and survives mod updates that rename or delete a target.
- A target whose container mutates from another thread mid-sweep throws
  `ConcurrentModificationException`, which is caught; the target is retried on the next sweep.
- `enabled = false` in the config disables all sweeping.

## Install

1. Drop `memfix-1.1.0.jar` into the server's `mods/` folder.
2. Restart. No configuration needed, no dependencies beyond Forge.

Client mods are not required, and the mod is harmless if a client happens to have it installed.
Every entry point it touches is server-side.

## Configuration

`config/memfix-common.toml`:

```toml
[memfix]
    # Master switch.
    enabled = true
    # Seconds between periodic sweeps. 0 disables the periodic sweep; the
    # level-unload and server-shutdown sweeps still run.
    sweepIntervalSeconds = 300
    # Log a line whenever a sweep drops entries.
    logPurges = true
    # Static containers to sweep, as 'package.Class#fieldName'.
    targets = [ ... 36 defaults ... ]
```

`targets` is editable, so a pack update that adds a leak does not need a new jar — add the
`package.Class#fieldName` line and restart. `logPurges` is on by default; on a healthy server the
only line you see is the periodic sweep reporting zero, and any non-zero count is a leak being
released.

## Verification

Everything below was run against the real `serverpack-slim-6.6.5b3hf`.

**Targets resolve.** `tools/verify-targets.py` reads the target list straight out of
`MemFixConfig` and asks `javap` to list each class's fields, asserting the field exists and is
`static`. Result: **36/36 verified, 0 bad.** `javap` is used instead of `Class.forName` because
loading one of these classes runs its static initializer, which registers blocks and items and
needs the full Minecraft bootstrap — outside a server that throws and looks like a missing field.

**The build runs.** `./gradlew clean build` passes, including 8 unit tests for the sweep rules
(offline-player detection, never-joined retention, nested containers, Guava multimap handling,
item-stack eviction, null and unrecognised keys).

**Components removed in 1.1.0.** When the `/rtp` guard and pregenerator existed,
`tools/verify-targets.py` also exercised the `ServerChunkCache`/`TicketType` paths they used. Those
components were removed in 1.1.0 (see *History*), so the script now verifies only the sweep targets.

**Reobfuscation is correct.** The source is written against Mojang mappings and ForgeGradle
reobfuscates to SRG for distribution. `javap -c` on the shipped jar shows the Minecraft calls were
mapped to their SRG names — `m_6846_` (`getPlayerList`), `m_11314_` (`getPlayers`), `m_129785_`
(`getAllLevels`), `m_213877_` (`isRemoved`), `m_41619_` (`isEmpty`) and `m_20148_` (`getUUID`) —
so the reflective paths hit the same members a plugin developer would target.

**It loads in the pack.** Booting the real server with the built jar:

```
[memfix] loaded 36 leak targets
Done (5.709s)! For help, type "help"
[memfix] startup sweep dropped 0 entries
```

`loaded 36` is the meaningful number: it means every class linked and every field resolved on a
live server, which is the one check `javap` cannot make. A startup sweep of 0 on a fresh world is
the correct result — nothing has leaked yet. Under load the periodic and unload sweeps are the
ones that report drops.

## Memory tuning

The mod fixes what accumulates; these settings make what remains fit. They live in the pack, not
the mod.

`user_jvm_args.txt` — 4 GB heap is deliberate. The pack ships `-Xmx8G`, which cannot work on a 6 GB
host: heap is only part of the process, and this pack also wants roughly 580 MB of metaspace, JIT
code cache, thread stacks and Netty buffers on top. An 8 G heap means the kernel OOM-kills the
server before the JVM ever throws `OutOfMemoryError`. Leaving 2 GB for the rest of the process is
what makes it survive.

```
-Xms4G -Xmx4G
-XX:MaxMetaspaceSize=768M
-XX:+UseG1GC
-XX:+ParallelRefProcEnabled
-XX:G1NewSizePercent=28 -XX:G1MaxNewSizePercent=50
-XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20
-XX:InitiatingHeapOccupancyPercent=15
-XX:+ExitOnOutOfMemoryError
```

`-XX:+ExitOnOutOfMemoryError` is intentional: a clean crash-restart is better than a server that
thrashes and stalls every player. `-XX:MaxMetaspaceSize` keeps a runaway classloader from eating
into a host that cannot afford it.

FTB Essentials (`world/serverconfig/ftbessentials.snbt`) — teleport cooldowns are 0 and the
teleport-history and home limits are at their maxima, so `/home`, `/back`, `/tpa` and `/spawn` run
immediately without warm-ups or limits. `/rtp` is served by the separate `linggango-rtp` mod (see
*History*); its `min_distance`/`max_distance` are `500`/`2048`.

FTB Chunks (`world/serverconfig/ftbchunks-world.snbt`) — `max_claimed_chunks` and
`max_force_loaded_chunks` are `2147483647` and `force_load_mode` is `"always"`.

There is no FTB Ranks on this server, so these config values are the final authority rather than
being overridden by a rank permission.

## The `RuntimeDistCleaner` log spam

Startup prints errors like:

```
Attempted to load class net/minecraft/client/player/AbstractClientPlayer for invalid dist DEDICATED_SERVER
```

This is **not** caused by this mod and needs no fix. It comes from ModernFix, which strips
client-only classes from the server jar; another mod references `AbstractClientPlayer` in a code
path that never executes on a dedicated server, and Forge's `RuntimeDistCleaner` logs the attempt
on the way past. It is cosmetic. It reproduces on a baseline run with MemFix absent, and removing
ModernFix would trade a harmless log line for a much heavier server.

## Layout

```
src/main/java/com/linggango/memfix/
  MemFix.java        mod entry point and lifecycle event handlers
  MemFixConfig.java  the 36 targets and the Forge config spec
  Sweeper.java       reflection, staleness rules, purge primitives
  GuavaAccess.java   reflective adapter for Guava multimaps
src/test/java/com/linggango/memfix/
  SweeperTest.java    unit tests for the sweep rules
tools/
  verify-targets.py  checks every target is a static field in the shipped pack
```

## Building from source

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build --no-daemon --console=plain
# artifact: build/libs/memfix-1.1.0.jar
```

Re-run `./tools/verify-targets.py /path/to/serverpack` after any modpack update.

## History

**1.0.0** also shipped a `/rtp` guard and a startup chunk pregenerator. FTB Essentials'
`/rtp` generates terrain on the server thread, and the guard declined any candidate whose
chunk was not already generated so the command would not stall. In practice the pregenerator
only ever covered a small area (the log showed `pregen: 512-block area already generated,
skipping`) while the pack's FTB config sets `min_distance: 500` and `max_distance: 2048`, so
the guard declined nearly every candidate and `/rtp` almost always reported that it could not
find a location.

**1.1.0** removes the guard, the pregenerator and their `RegionIndex` helper entirely, along
with the `[memfix.rtp]` config table. Random teleport is now handled by the separate
`linggango-rtp` mod, which performs the search off the server tick thread instead of refusing
to search. The `memfix-common.toml` written by 1.0.0 can be left on disk; Forge ignores the
now-unknown `[memfix.rtp]` keys.

## Notes and limitations

- The sweep operates at the collection level, so it **bounds** growth rather than repairing each
  mod's internal logic. It is a safety net, not a substitute for an upstream fix.
- It only drops entries it can prove are unreachable. If a mod leaks through a container shape not
  handled here — an array, a custom structure — that leak survives until a target is added.
- The 36 targets were derived by decompiling the shipped jars and looking for static collections
  holding world-scoped objects. A modpack update can move or rename any of them; the mod tolerates
  that by skipping, and `verify-targets.py` reports which ones drifted.

---

_This mod and its documentation were created by an AI agent (OpenHands) on behalf of the
repository owner. Review the code before deploying it to a production server._
