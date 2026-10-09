# AGENTS.md — Linggango Sync

Repository-specific knowledge for agents working on this mod.

## Project

Forge mod (`linggangosync`) adding a networking/data-synchronisation optimisation layer for
the Linggango modpack. Target is **MC 1.20.1 / Forge 47.4.20 / Java 17** — confirmed against
the real serverpack, not assumed.

## Build

```bash
export JAVA_HOME=/opt/java/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:/opt/gradle/gradle-8.5/bin:$PATH
gradle clean build --no-daemon     # runs tests; jar -> build/libs/
```

78 tests. Benchmark tests print measurements to stdout, which is why `testLogging` in
`build.gradle` sets `showStandardStreams = true` — without it those numbers are invisible.

## Live testing without a GPU

The dev box has no GPU. A client still runs via Mesa `llvmpipe` + Xvfb. Details, including
the harmless audio/narrator errors and the `--quickPlayMultiplayer` auto-join trick, are in
`docs/HEADLESS_TESTING.md`.

Key traps:

- **`gradle runServer` ignores stdin.** Piping console commands in does nothing. Add
  `standardInput = System.in` to the server run config, or read the on-stop report by
  SIGTERM-ing the server JVM.
- **Logs stall during terrain load**, which looks like a hang. Use `jstack` on the render
  thread to tell real progress from a deadlock.
- Identify processes by `launchTarget forgeserveruserdev` / `forgeclientuserdev` in
  `/proc/<pid>/cmdline`, since several Gradle wrappers share the `java` name.

## Core invariant

Client-local storage is only ever acceptable when cheating the local copy cannot produce an
unauthorized server-side result. Missing adapter, unregistered type, unparsable config, bad
checksum, corrupt cache, version/hash mismatch — all must resolve to **normal synchronisation**.
Fail closed.

Do not relax a default to `CLIENT_LOCAL` to save bytes. `player_state`, `inventories`,
`world_blocks`, `mobs`, `projectiles`, `redstone`, `containers`, `machine_inventory` and
`create_contraptions` stay `SERVER_AUTHORITATIVE`.

## Deliberate non-optimisations

Create is intentionally given no optimisations: 153 networking classes, plus `linggango_tweaks`
already integrates with it, and contraption state affects collisions and item movement.
Do not "fix" this without evidence.

## Packet attribution

Attributing by class name alone is unreliable — 393 of the pack's 407 mods contain networking
classes. The monitor resolves the `SimpleChannel` name first and only falls back to class name.

Note that `connectivity` also mixins `PacketEncoder` (twice), and `Immersive Optimization` is
present. When chasing attribution, measure with only one optimisation layer active.

## Version control

- `dist/` is ignored: the jar ships as a GitHub Release asset, never committed.
- `run/` is ignored: dev runs create worlds, configs and logs there.
- Release process and the pre-publish checklist live in `docs/RELEASE.md`.
- Release assets on this **private** repo return `Not Found` to anonymous downloads; use the
  asset API endpoint or a logged-in browser.

## Live test environment (this box)

- No GPU: software GL only (Xvfb + Mesa `llvmpipe`). Java 17 at
  `/workspace/toolchain/jdk-17.0.20.1+1`.
- A runtime restart reverts everything outside `/workspace` (apt packages, `/tmp`, `/opt`);
  the JDK and the `prodclient`/`prodserver` installs survive under `/workspace`.
- The machine has 4 CPUs, ~16 GB RAM, **no swap** (and `swapon` is not permitted), so a
  400-mod server plus a software-GL client are permanently near the OOM ceiling. A runtime
  memory error restarts the process tree; check `uptime` before assuming a crash cause.
- Full boot recipe and the production-client join capture are in `docs/HEADLESS_TESTING.md`.
  Key points: exclude `lazyyyyy` from the client (off-thread `DynamicTexture` crash), set
  `max-tick-time=-1`, and pin server/client to disjoint cores (`taskset -c 0,1` / `taskset -c 2,3`).
- Server console stdin is not consumed in this setup; drive commands through RCON
  (`enable-rcon=true`, port 25575) with `/workspace/rcon.py`.

## linggangoopt (datapack login cache)

A second mod, `linggangoopt/` (com.linggango.opt), removes the recipe/tag datapack resend on
reconnect. See `docs/LINGGANGOOPT.md` for the full report. Gotchas worth not rediscovering:

- Client hashes the **exact wire bytes**, captured at the head of `PacketDecoder.decode` with
  `ByteBuf.getBytes` (no reader advance). Re-serialising a parsed packet to hash it is **not**
  byte-stable — do not go back to handler-based capture.
- `ClientboundUpdateRecipesPacket`/`Tags` hashes are **run-dependent** (live map iteration order),
  so they change across server restarts. The server only compares against the hash it computes
  now; stale claims just miss. Do not try to make them persistent.
- ModernFix `mixin.perf.smart_ingredient_sync` rewrites the recipes packet from per-player state,
  so the recipes skip only fires when it is `false` in `config/modernfix-mixins.properties` on
  both sides. Tags are unaffected.
- The skip path is fail-closed: a failed client replay sends `ResendRequest`, the server resends
  and clears the claim. Never let a replay failure strand the client or disable hooks for the
  session.
- Enable the saving server-side with `skip_server_send_on_hash_hit=true` (default false).


