# Linggango RTP

A server-side Forge mod for the **Linggango 6.6.5b3hf** modpack (Minecraft 1.20.1, Forge 47.4.20,
Java 17). It replaces FTB Essentials' synchronous `/rtp` location search with an EssentialsX-style
asynchronous one, so a random teleport can reach terrain that has never been generated without
stalling the server thread.

> **Status:** not yet executed against a live client; deploy pending go/no-go. The mod is fully
> implemented, builds cleanly and passes its unit suite (21 tests), but no Minecraft client
> exists in the build environment, so the `/rtp` path has never run against the game. See
> [`docs/DEPLOY.md`](docs/DEPLOY.md) before installing it anywhere.

## Intent

FTB Essentials' `/rtp` picks a random candidate and then blocks the calling (server) thread while
the chunk generates, once per candidate up to `max_tries` times. On the Linggango pack that means
players can only ever be placed in terrain that is already generated, and each attempt in fresh
terrain freezes every other player for the duration of the generation.

This mod owns `/rtp` and nothing else. It picks candidates across a bounded spread, loads each
chunk **off the server tick thread** (on a dedicated daemon worker), validates the landing on the
server thread when the chunk is ready, retries a bounded number of times, and then delegates the
actual teleport back to FTB so cooldowns, warmups, teleport history and dimension rules are
unchanged.

## Non-goals

- Replacing FTB's other teleport commands (`/home`, `/back`, `/tpa`, `/warp`, `/spawn`).
- Reaching the full 30M world border.
- Touching other dimensions: `/rtp` may only land in the overworld; elsewhere FTB's behaviour
  stands untouched.
- Any main-thread terrain generation.

## How it works (design)

- Intercepts `/rtp` at Forge's `CommandEvent`, cancels FTB's synchronous implementation, and runs
  its own search. Interception happens **only in the overworld**, only while FTB reports no active
  cooldown, only while FTB Essentials is present, only while FTB's own `RTP.enabled` config gate is
  on, and only while the mod is `enabled`. In every other case the event is left alone and FTB's
  own path (including its cooldown message, warm-up, End blacklist and history) runs untouched.
- Picks a uniformly-by-area random point within a configured radius (default 32,000 blocks) of a
  configurable center (default world origin), clamped to the world border.
- Runs the whole candidate loop on a dedicated daemon worker thread this mod owns, so
  `ServerChunkCache.getChunkFuture` is called **off the server tick thread**. In 1.20.1 that call
  schedules the chunk load on the main-thread queue and returns immediately; called on the server
  thread it would instead `managedBlock` (stall) the tick until the chunk is FULL. The server
  thread only ever enqueues work, never waits.
- Validates the landing **on the server thread** (solid, safe, open ground; not an excluded biome
  such as ocean or river; not in FTB's `ftbessentials:ignore_rtp` biome or block tag; not a foreign
  FTB Chunks claim), retrying up to N times. A retry is re-scheduled onto the worker, not recursed
  on the server thread.
- Hands the found position to FTB's own `rtpTeleporter.teleport(...)` so FTB still applies the
  cooldown, dimension blacklist, warm-up and teleport history. A rapid second `/rtp` therefore
  still gets FTB's cooldown message, and `/back` still works because FTB recorded the teleport.
- Runs at most one search per player at a time (single-flight); a second `/rtp` while one is
  pending is refused with `already searching`.
- Cancels any in-flight search when the player logs out, changes dimension, dies, or the server
  stops (the worker is shut down on server stop), so a callback can never teleport a player who is
  gone.
- Fails gracefully: if no valid spot is found after `findAttempts`, tells the player
  `Could not find a valid location` and does not teleport.

All FTB access is reflective, so the mod compiles against Forge alone (no mod dependencies in
`build.gradle`) and does nothing if FTB Essentials is absent.

### FTB's own `/rtp` gates are honored

FTB Essentials' decompiled `TeleportCommands` rejects biomes in the `ftbessentials:ignore_rtp`
biome tag, rejects surface blocks in the `ftbessentials:ignore_rtp` block tag (e.g.
`#minecraft:leaves`, `minecraft:bedrock`), and only registers `/rtp` at all when
`FTBEConfig.RTP.isEnabled()` is true. All three are replicated here, reflectively (no compile-time
FTB dependency): the mod's config `excludedBiomes` list is **merged with** — not a replacement for
— the biome tag, and interception is suppressed entirely when FTB's own gate is off so the command
behaves exactly as stock FTB. These lookups fail safe: a tag that cannot be resolved is treated as
absent (no tag rejection), and the `RTP.enabled` gate resolves to `false` on any failure (no
interception). The FTB cooldown check also **fails closed** (a broken reflective call is treated as
"on cooldown" so the command falls through to FTB's native path); a failure of the command hook
itself is logged once, not swallowed silently.

### Relationship to the memfix guard

When this mod successfully intercepts, FTB's own RNG path — and therefore the `linggango-memfix`
`RtpGuard` — does **not** run. The memfix guard is kept as a fallback and should only be turned
off once this mod is proven on the live server. Do not disable it before then.

## Configuration

The config is written to `config/linggango-rtp-common.toml` (note the hyphenated name — it is
fixed in code and does not follow the mod id). All keys live under the `[rtp]` table:

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `enabled` | boolean | `true` | Master switch. When `false` the mod does not intercept `/rtp`. |
| `centerX` | int | `0` | Centre X of the search area. Range `-30000000..30000000`. |
| `centerZ` | int | `0` | Centre Z of the search area. Range `-30000000..30000000`. |
| `minRadius` | int | `0` | Minimum distance from the centre, in blocks. Range `0..30000000`. |
| `maxRadius` | int | `32000` | Maximum distance from the centre, in blocks. Range `0..30000000`. |
| `findAttempts` | int | `10` | Candidates to try before giving up. Range `1..1000`. |
| `respectClaims` | boolean | `true` | Skip candidates inside an FTB Chunks claim the player is not a member/ally of. |
| `excludedBiomes` | list of `namespace:path` | see below | Biomes `/rtp` will not land in. |

Every getter falls back to the default above if the config is not yet loaded, so a read before
Forge finishes config loading is safe.

Default `excludedBiomes` (EssentialsX's ocean/river set plus the Linggango additions):

```
minecraft:ocean
minecraft:deep_ocean
minecraft:cold_ocean
minecraft:deep_cold_ocean
minecraft:frozen_ocean
minecraft:deep_frozen_ocean
minecraft:lukewarm_ocean
minecraft:deep_lukewarm_ocean
minecraft:warm_ocean
minecraft:deep_warm_ocean
minecraft:river
minecraft:frozen_river
minecraft:deep_dark
minecraft:mushroom_fields
minecraft:the_void
minecraft:small_end_islands
minecraft:end_highlands
minecraft:end_midlands
minecraft:end_barrens
```

Biome matching is case-insensitive.

## Install

1. Drop `linggango_rtp-1.0.0.jar` into the server's `mods/` folder.
2. Restart. No client mod is required.

FTB Essentials is a soft dependency: it is not required to load the mod, but the mod's behaviour
only applies where FTB Essentials is present.

**Deploying to the live server** (and retiring the `linggango-memfix` `/rtp` guard) is a
production side-effect and needs a human go/no-go: see [`docs/DEPLOY.md`](docs/DEPLOY.md) for the
exact SFTP, backup, edit, restart, smoke-test and rollback steps.

## Building from source

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
sh gradlew clean build --no-daemon --console=plain
# artifact: build/libs/linggango_rtp-1.0.0.jar
```

`gradlew` is committed non-executable (matching the sibling mods), so invoke it as `sh gradlew`
or `chmod +x gradlew` first. The first build downloads and decompiles Minecraft through
ForgeGradle and takes a few minutes; the unit suite (21 tests) runs as part of `build`.

## Release verification

- Build: `BUILD SUCCESSFUL`, **21 tests passed, 0 failed, 0 skipped**.
- Artifact: `build/libs/linggango_rtp-1.0.0.jar`, 24,288 bytes, SHA-256
  `819cfe4c6ad1da99959809394b549425aca8ffd3e73c9e493158229c628e1aa6`.
- The jar contains `META-INF/mods.toml`, `pack.mcmeta` and the seven mod classes; it bundles no
  FTB classes (FTB access is reflection-only).
- `mods.toml` declares modid `linggango_rtp` and a soft (`mandatory=false`) `ftbessentials`
  dependency.

## References

- Design: `docs/superpowers/specs/2026-10-10-linggango-rtp-design.md`
- Plan: `docs/superpowers/plans/2026-10-10-linggango-rtp.md`
- Deploy runbook: `docs/DEPLOY.md`

---

_This mod and its documentation were created by an AI agent (OpenHands) on behalf of the
repository owner. Review the code before deploying it to a production server._
