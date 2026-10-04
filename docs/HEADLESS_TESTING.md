# Headless client testing (no GPU required)

This mod was live-tested on a machine with **no GPU, no `/dev/dri`, and no display**, using
Mesa's `llvmpipe` software renderer under Xvfb. Minecraft 1.20.1 only needs OpenGL 3.2;
`llvmpipe` provides 4.5, so a client runs fine — slowly, but correctly.

This is useful because the interesting behaviour (cache, delta sync, interest management,
packet attribution) happens during the network handshake and chunk/entity tracking, which
do not need real GPU speed.

## One-time setup

```bash
sudo apt-get install -y xvfb mesa-utils libgl1-mesa-dri

# Confirm software GL meets Minecraft's OpenGL 3.2 requirement:
Xvfb :99 -screen 0 1280x720x24 &
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 glxinfo -B | grep "core profile version"
# -> OpenGL core profile version string: 4.5 (Core Profile) Mesa ...
```

## Launching a headless client

```bash
export DISPLAY=:99
export LIBGL_ALWAYS_SOFTWARE=1
export MESA_GL_VERSION_OVERRIDE=4.5

gradle runClient --no-daemon --args="--quickPlayMultiplayer localhost:25565"
```

`--quickPlayMultiplayer` (a vanilla 1.20.1 Main argument) auto-joins the server, so no
GUI automation is needed. Without it the client parks in the main menu.

## Expected failures that are harmless

| Message | Meaning |
| --- | --- |
| `Failed to open OpenAL device` | No audio device. Sounds are disabled; gameplay unaffected. |
| `Error while loading the narrator` (`Failed to load library flite`) | Text-to-speech absent. Cosmetic only. |

## Confirming the client is actually in-game

Log output stalls during terrain load, which can look like a hang. Check the render thread
instead:

```bash
PID=$(ps aux | grep "[f]orgeclientuserdev" | grep -v defunct | awk '{print $2}' | head -1)
jstack $PID | grep -A4 '"Render thread"'
```

- `Minecraft.run -> limitDisplayFPS -> glfwWaitEventsTimeout` = main menu, idle.
- `ClientPacketListener.handleMoveEntity` = **in-game, receiving live packets**.

Software rendering is slow, so allow several minutes for the first world load.

## Sending console commands to the dev server

ForgeGradle's `runServer` does **not** forward stdin to the server process, so piping into
`gradle runServer` silently does nothing. Use one of these instead:

- Add `standardInput = System.in` to the `server` run config in `build.gradle`, then pipe
  commands in; or
- Read the shutdown report: `SERVER_AUTHORITATIVE` metrics are logged on
  `ServerStoppingEvent` when `debug.autoReportOnStop` is true. Send `SIGTERM` to the server
  JVM (identify it by `launchTarget forgeserveruserdev` in `/proc/<pid>/cmdline`) to make it
  log the final report cleanly.

## Measured result

A gravity-cube-slow session (client loading terrain, no player input) produced:

```
Network Statistics (window 4m 37s)
  total: 164,790 packets, 8.0 MB  (1.7 MB/min)
  average packet: 51 B

  S->C: 164,790 packets, 8.0 MB  (1.7 MB/min)

  By mod (top 20)
    Vanilla    164,765 packets   7.8 MB
    fml             22 packets 128.2 KB
    forge            1 packets     121 B
    minecraft        2 packets     114 B
```

Two things this validates:

1. The mixin hooks the real outbound path and sees **every** packet, at true wire size.
2. Attribution works — vanilla chunk/entity traffic dominates, and `fml`'s handshake
   channel is correctly separated from it.

A single client loading terrain is not a useful bandwidth benchmark; it exists to prove the
instrumentation is correct. Real optimisation decisions need a populated server.

## Production-client run against the full Linggango pack

ForgeGradle `runClient` (userdev) cannot boot this pack: several mods ship raw SRG names
(`alltheleaks` -> `GsonHelper.m_13855_`, `neobackports` -> `@Shadow m_5483_`) or Fabric-only
metadata (`jasione`), which the userdev remapper rejects even though production accepts them.
Test with a **production** install instead: run the official Forge installer, then launch the
generated `libraries/` + `versions/` classpath directly with the vanilla `--gameDir`/`--assetsDir`
arguments. A full 1.20.1 asset store (3598 objects, keyed by the `hash` field of the asset
index — not the resource path) is required.

Result with all 429 client mods and our mod present:

```
[Render thread/INFO] [minecraft/Minecraft]: Setting user: TestPlayer
[Worker-ResourceReload/INFO] [LinggangoSync/]: Linggango Sync ready: 60 descriptors
[Worker-ResourceReload/INFO] [LinggangoSync/]: Client cache ready at .../linggangosync/cache.bin
[ConnectScreen]: Connecting to localhost, 25565
[NetworkHooks]: Connected to a modded server.
[PlayerList]: TestPlayer[/127.0.0.1:52336] logged in with entity id 1297
[MinecraftServer]: TestPlayer joined the game
```

Live join-synchronisation capture on the server (`/linggango-net types`), which is exactly the
class of data this mod exists to measure:

```
S->C Vanilla / ClientboundUpdateRecipesPacket                1    5.5 MB
S->C Vanilla / ClientboundLevelChunkWithLightPacket        109    5.4 MB
S->C architectury / CustomPayload                          163    4.2 MB  (max 3.6 MB single packet)
S->C fml / CustomPayload                                   296    2.3 MB
S->C Vanilla / ClientboundLoginPacket                        1  704.2 KB
S->C Hexerei / CustomPayload                                 3  596.9 KB
S->C Vanilla / ClientboundUpdateTagsPacket                   1  549.2 KB
S->C tiered / CustomPayload                                  3  369.4 KB
```

The single 5.5 MB `ClientboundUpdateRecipesPacket` and the 3.6 MB `architectury` payload are the
on-demand / cacheable candidates; recipes are identical for every player and never change while
the server is up.

## Two environment gotchas that masquerade as mod bugs

- **`lazyyyyy` + software GL**: its lazy entity-renderer coroutine constructs a `DynamicTexture`
  off the render thread, which crashes `Joy of Painting` during the menu load
  (`Rendersystem called from wrong thread`). Unrelated to this mod; exclude `lazyyyyy` from the
  client (it adds no blocks/items, so there is no registry mismatch) to reach the menu.
- **Server watchdog under CPU contention**: with a software-GL client and a 400-mod server on
  4 cores, the client starves the server tick thread and the vanilla watchdog kills the server
  (`ServerHangWatchdog ... tick took 60.00 seconds`). Set `max-tick-time=-1` and pin the server
  and client to disjoint cores (`taskset -c 0,1` / `taskset -c 2,3`) so the login burst completes.

## Gotcha: `run/` is gitignored

Dev runs create `run/client1/`, `run/server/`, world saves and stray `logs/`. All are
ignored, so a live test never dirties the working tree.
