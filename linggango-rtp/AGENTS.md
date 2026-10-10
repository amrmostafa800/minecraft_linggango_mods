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
and takes a few minutes. Later builds are seconds. The unit suite (18 tests) runs as part of
`build`.

## Status

**Not yet executed against a live client; deploy pending go/no-go.** The mod is fully implemented,
`sh gradlew clean build` is `BUILD SUCCESSFUL` with 18 tests passing (0 failed, 0 skipped), and the
built jar was statically inspected — but this environment has no Minecraft client, so the `/rtp`
path has never run against the game. Installing it on the live server (and turning off the memfix
`/rtp` guard) is a production side-effect that needs a human go/no-go. See
[`docs/DEPLOY.md`](docs/DEPLOY.md).

## Behaviour

- Intercepts `/rtp` on Forge's `CommandEvent` at `HIGHEST` priority and cancels FTB's synchronous
  implementation, running its own search instead.
- **Overworld only.** In the Nether or End the event is not cancelled; FTB's path (including its
  End blacklist) runs untouched.
- Only intercepts while FTB reports **no cooldown**, FTB Essentials is **present**, and the mod is
  **`enabled`**. Otherwise the command falls through to FTB unchanged.
- Picks a uniformly-by-area candidate in `[minRadius, maxRadius]` around `(centerX, centerZ)`,
  clamped to the world border when the border is smaller than `maxRadius`.
- Loads the candidate chunk via `ServerChunkCache.getChunkFuture`; validates the landing (biome not
  excluded, safe Y, not a foreign FTB Chunks claim) in the future's callback on the server thread;
  retries up to `findAttempts`.
- Delegates the final teleport to FTB's `rtpTeleporter.teleport(...)`, so FTB's cooldown message,
  warm-up, dimension blacklist and teleport history (and therefore `/back`) still apply.
- Single-flight per player; a search is cancelled on logout, dimension change, death and server
  stop. On exhaustion it tells the player `Could not find a valid location`.

## Configuration

`config/linggango-rtp-common.toml` (hyphenated name is fixed in code, not derived from the mod id),
under the `[rtp]` table. Getters fall back to these defaults if the config is not yet loaded:

| Key | Default | Notes |
| --- | --- | --- |
| `enabled` | `true` | Master switch; `false` disables interception. |
| `centerX` | `0` | Search centre X (`-30000000..30000000`). |
| `centerZ` | `0` | Search centre Z (`-30000000..30000000`). |
| `minRadius` | `0` | Minimum distance from centre (`0..30000000`). |
| `maxRadius` | `32000` | Maximum distance from centre (`0..30000000`). |
| `findAttempts` | `10` | Candidates to try before giving up (`1..1000`). |
| `respectClaims` | `true` | Skip candidates in a foreign FTB Chunks claim. |
| `excludedBiomes` | 19 defaults | Biomes `/rtp` will not land in; case-insensitive. |

Default `excludedBiomes`: the eight ocean variants, `river`, `frozen_river`, `deep_dark`,
`mushroom_fields`, `the_void`, `small_end_islands`, `end_highlands`, `end_midlands`, `end_barrens`.

## Deploying

The deploy runbook is [`docs/DEPLOY.md`](docs/DEPLOY.md): exact SFTP target
(`fr-zero.acorehosting.com:2022`, chrooted), backup of `config/memfix-common.toml`, jar upload,
guard/pregenerate edit, restart, smoke test, and rollback. The password is an environment-provided
secret and is never written to the repo.

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
