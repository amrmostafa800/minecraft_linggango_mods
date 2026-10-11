# Linggango RTP (`linggango_rtp`)

Server-side `/rtp` replacement for the Linggango modpack.
MC 1.20.1 / Forge 47.4.20 / Java 17.

## Build

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
sh gradlew clean build --no-daemon --console=plain
# artifact: build/libs/linggango_rtp-1.0.0.jar
```

`gradlew` is committed non-executable (matching `linggango-memfix`); invoke it as `sh gradlew` or
`chmod +x gradlew` first. The first build downloads and decompiles Minecraft through ForgeGradle
and takes a few minutes. Later builds are seconds. The unit suite (21 tests) runs as part of
`build`.

## Status

**Deployed to the live server 2026-10-11; not yet verified in-game.** 1.1.0 is the current artifact:
`sh gradlew clean build` is `BUILD SUCCESSFUL` with 27 tests passing (0 failed, 0 skipped), and the
built jar was statically inspected - but this environment has no Minecraft client, so the `/rtp`
path has never run against the game. 1.0.0's first live `/rtp` stalled the server (up to 24.6 s,
491 ticks behind) because it generated terrain; 1.1.0 adds the on-disk gate described below to stop
that. The memfix `/rtp` guard was removed in the same change (memfix 1.1.0), so there is no
fallback. Run the acceptance test in [`docs/DEPLOY.md`](docs/DEPLOY.md) after the next server start
and roll back if it fails.

## Behaviour

- Intercepts `/rtp` on Forge's `CommandEvent` at `HIGHEST` priority and cancels FTB's synchronous
  implementation, running its own search instead.
- **Overworld only.** In the Nether or End the event is not cancelled; FTB's path (including its
  End blacklist) runs untouched.
- Only intercepts while FTB reports **no cooldown**, FTB Essentials is **present**, FTB's own
  **`RTP.enabled`** config gate is on, and the mod is **`enabled`**. Otherwise the command falls
  through to FTB unchanged.
- Picks a uniformly-by-area candidate in `[minRadius, maxRadius]` around `(centerX, centerZ)`,
  clamped to the world border when the border is smaller than `maxRadius`.
- **Rejects ungenerated candidates before requesting the chunk.** With `existingChunksOnly` (default
  on), `ChunkIndex` reads the Anvil region-file header (8 KiB, no chunk pipeline) and the candidate
  is skipped unless its chunk **and** `chunkMargin` neighbours are already on disk. This is the fix
  for 1.0.0's stall: requesting a chunk at `FULL` **generates** it when absent, and generation is the
  expensive, tick-stalling work. The header cache is dropped each server tick so freshly generated
  terrain becomes eligible.
- Runs the candidate loop on a dedicated daemon worker thread, so `ServerChunkCache.getChunkFuture`
  is called **off the server tick thread** (in 1.20.1 that schedules the load and returns
  immediately; on the server thread it would `managedBlock`/stall). The immutable geometry
  (`ServerLevel`, border half-size, radii) is captured on the server thread at `start`; the worker
  reads no world state.
- Validates the landing on the **server thread** (biome not excluded by config or FTB's
  `ftbessentials:ignore_rtp` biome tag, safe Y, surface not in FTB's `ftbessentials:ignore_rtp`
  block tag, not a foreign FTB Chunks claim); retries up to `findAttempts`, re-scheduling retries
  onto the worker rather than recursing on the server thread.
- Delegates the final teleport to FTB's `rtpTeleporter.teleport(...)`, so FTB's cooldown message,
  warm-up, dimension blacklist and teleport history (and therefore `/back`) still apply.
- Single-flight per player; a search is cancelled on logout, dimension change, death and server
  stop (the worker is shut down on server stop). On exhaustion it tells the player
  `Could not find a valid location`.

### FTB gate parity and fail-safe direction

FTB's decompiled `TeleportCommands` rejects the `ftbessentials:ignore_rtp` **biome** and **block**
tags and registers `/rtp` only when `FTBEConfig.RTP.isEnabled()`. All three are replicated
reflectively. `excludedBiomes` is merged with the biome tag (not a replacement). Fail-safe
directions: unresolved tags are treated as absent (no rejection); `RTP.enabled` resolves to `false` on any
failure (no interception); the FTB cooldown check **fails closed** (`true` = "on cooldown" = do
not intercept); a `Throwable` in the command hook is logged once and the command still falls through
to FTB.

### memfix relationship (removed)

`linggango-memfix` 1.0.0 shipped an `RtpGuard` that declined `/rtp` candidates whose chunk was not
already generated. It made `/rtp` useless (its 512-block pregenerated area is far smaller than the
pack's `rtp.max_distance: 2048`), so memfix 1.1.0 **removed the guard and pregenerator entirely**.
When this mod intercepts, FTB's RNG path and any guard do not run; there is now no fallback.


## Configuration

`config/linggango-rtp-common.toml` (hyphenated name is fixed in code, not derived from the mod id),
under the `[rtp]` table. Getters fall back to these defaults if the config is not yet loaded:

| Key | Default | Notes |
| --- | --- | --- |
| `enabled` | `true` | Master switch; `false` disables interception. |
| `centerX` | `0` | Search centre X (`-30000000..30000000`). |
| `centerZ` | `0` | Search centre Z (`-30000000..30000000`). |
| `minRadius` | `500` | Minimum distance from centre (`0..30000000`). |
| `maxRadius` | `2048` | Maximum distance from centre (`0..30000000`). |
| `findAttempts` | `40` | Candidates to try before giving up (`1..1000`). |
| `existingChunksOnly` | `true` | Reject candidates not already on disk (never generate terrain). |
| `chunkMargin` | `1` | Neighbour margin required on disk, in chunks (`0..2`); `1` = full 3x3. |
| `respectClaims` | `true` | Skip candidates in a foreign FTB Chunks claim. |
| `excludedBiomes` | 19 defaults | Biomes `/rtp` will not land in; case-insensitive. |

Default `excludedBiomes`: the eight ocean variants, `river`, `frozen_river`, `deep_dark`,
`mushroom_fields`, `the_void`, `small_end_islands`, `end_highlands`, `end_midlands`, `end_barrens`.

## Deploying

The deploy record is [`docs/DEPLOY.md`](docs/DEPLOY.md): the SFTP target
(`fr-zero.acorehosting.com:2022`), what was backed up and deployed on 2026-10-11, the post-start
acceptance test, tuning, and rollback. The password is an operator-provided secret and is never
written to the repo.

## Invariants to preserve (from the plan)

1. **No main-thread terrain generation.** Candidates are loaded through
   `ServerChunkCache.getChunkFuture`; nothing may block the server tick waiting on a chunk.
   `existingChunksOnly` must stay default-on so a search never requests a chunk that does not exist
   (which generates it). The region-header cache must be invalidated at least once per tick.
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
