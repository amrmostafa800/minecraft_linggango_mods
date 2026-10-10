# Linggango RTP (`linggango_rtp`)

Server-side `/rtp` replacement for the Linggango modpack.
MC 1.20.1 / Forge 47.4.20 / Java 17.

## Build

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build --no-daemon --console=plain
# artifact: build/libs/linggango_rtp-1.0.0.jar
```

The first build downloads and decompiles Minecraft through ForgeGradle and takes a few minutes.
Later builds are seconds.

## Status

Task 1 scaffold: the mod loads and logs `[rtp] loaded`. The asynchronous `/rtp` search is
implemented in later tasks; see `docs/superpowers/plans/2026-10-10-linggango-rtp.md`.

## Invariants to preserve (from the plan)

1. **No main-thread terrain generation.** Candidates are loaded through
   `ServerChunkCache.getChunkFuture`; nothing may block the server tick waiting on a chunk.
2. **Reflection only.** No compile-time dependency on `ftbessentials`, `ftbchunks` or `ftbteams`.
   `build.gradle` has no mod dependencies and the mod compiles against Forge alone.
3. **Delegate the teleport to FTB.** The final teleport goes through FTB's
   `rtpTeleporter.teleport(...)`, never a direct `player.teleportTo`, so FTB's cooldown, dimension
   blacklist, warm-up and teleport history still apply.
4. **Overworld only.** In the Nether or End the mod must not cancel `/rtp`; FTB's own path (and
   its End blacklist) runs untouched.
5. **`mods.toml` declares `ftbessentials` as a soft dependency** (`mandatory=false`), no
   load-order directive.
6. **Respect the world border.** A candidate outside a border smaller than `maxRadius` must be
   clamped inside it, never teleported outside.
7. **Fail gracefully.** After N attempts with no valid spot, tell the player and do not teleport.

## Gotchas

- The reobf step is what makes the jar load in the pack. Sources are written against Mojang
  official mappings; ForgeGradle reobfuscates to SRG for distribution.
- Package root is `com.linggango.rtp`; mod id `linggango_rtp`.
- The pack's `resourceful-config-web.json` holds a generated web password. It is a private-server
  credential; do not copy it into this repo or any public artefact.
