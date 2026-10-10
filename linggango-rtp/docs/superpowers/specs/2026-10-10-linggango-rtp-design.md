# Linggango RTP — design

Date: 2026-10-10
Status: approved behaviour, awaiting plan

## Intent and why

`/rtp` on the Linggango server (FTB Essentials, Forge mod) only lands players
back in terrain they have already visited. The cause is the `memfix` `/rtp`
guard: it declines any candidate chunk that is not already generated, and FTB
fires its decision event *before* loading the chunk. So `/rtp` can only ever
place someone inside the already-generated ring around the world origin. Players
cannot reach new biomes.

EssentialsX (a Spigot plugin) does not have this problem because it *loads* the
chosen chunk asynchronously, off the main thread, and teleports when it is
ready. This mod moves `/rtp` to the same model: a random point anywhere in the
allowed spread, chunk loaded off-thread, validating the landing.

This is a separate mod from `memfix` on purpose. `memfix` exists to release
memory leaks; it should not also own teleport behaviour. This mod owns `/rtp`
and nothing else. `memfix`'s guard and pregenerator should be retired once this
is proven (memfix config `[memfix.rtp] guard=false`, `pregenerate=false`).

## Goals

- `/rtp` reaches terrain that has never been generated, so new biomes are
  reachable.
- No server-thread stall: the candidate chunk is loaded/generated off the main
  thread; the player's `/rtp` command returns immediately with a "searching"
  message and the teleport happens when a valid spot is found.
- Bounded spread: candidates are picked within a configured radius (default
  32,000 blocks) of a configurable center (default world origin), not the full
  30,000,000-block world border, so world growth stays bounded.
- Land on solid, safe, open ground; never in excluded biomes (oceans, rivers by
  default).
- Respect the world border if it is smaller than the configured radius.
- Fail gracefully: if no valid spot is found in N attempts, tell the player and
  do not teleport.

## Non-goals

- Replacing FTB Essentials' other teleport commands (`/home`, `/back`, `/tpa`,
  `/warp`, `/spawn`). Only `/rtp` is changed.
- Reaching the full 30M world border.
- Cross-checking every other mod's teleport hooks.
- Keeping memfix's pregenerator. This mod does not pregenerate; it loads one
  chunk per attempt, on demand.

## Anti-goals (would make this a failure even if it works)

- Any main-thread terrain generation. A tick that blocks on chunk generation is
  the exact problem being removed.
- Landing players in lava, the void, mid-ocean, or inside walls.
- Silently teleporting into another player's claim without the pack's rules.
- A leak: caching every found location forever.

## Constraints

- MC 1.20.1, Forge 47.4.20, Java 17.
- Must coexist with FTB Essentials (which still owns the `/rtp` command and its
  cooldown/warm-up) and with FTB Chunks / FTB Teams, and with the pack's
  `difficulty_enhancement_ftbe` cooldown mod.
- Server-side only. Clients need nothing.
- Must compile in this repo's ForgeGradle toolchain (same build files as
  `linggango-memfix`).

## How it works (decided)

1. **Intercept.** On `CommandEvent` (highest priority), detect the `/rtp`
   command node. Cancel FTB's synchronous implementation and run our own.
   Other commands pass through untouched.
2. **Pick a candidate.** Center = config `centerX/centerZ` (default 0,0),
   radius = config `maxRadius` (default 32000), `minRadius` (default 0). Choose
   a uniformly-by-area point: `r = sqrt(uniform(minR², maxR²))`, `theta =
   uniform(0, 2π)`. Clamp to the world border if the border is smaller.
3. **Load off-thread.** `serverChunkCache.getChunkFuture(chunkX, chunkZ,
   ChunkStatus.FULL, true)` — this generates off the main thread. Do not block
   the main thread waiting on it.
4. **Validate when ready:**
   - surface Y is above the world minimum (not void)
   - biome not in `excludedBiomes`
   - the block at the surface and the two above are safe (not lava, not water,
     not fire; enough headroom)
   - `world.getBlockState` at the landing spot is solid
   - not inside another team's claimed chunk (FTB Chunks check, soft dependency)
5. **Retry.** Up to `findAttempts` (default 10). On exhaustion, message the
   player and stop.
6. **Teleport.** On a valid spot, teleport the player and send FTB's normal
   success feedback. Respect any cooldown FTB/`difficulty_enhancement_ftbe`
   would have applied, so `/rtp` spam is still rate-limited.

Run the whole search on a background thread or the chunk future's callback;
never on the server tick thread.

## Config (`config/linggango-rtp-common.toml`)

```
[rtp]
  enabled = true
  centerX = 0
  centerZ = 0
  minRadius = 0
  maxRadius = 32000
  findAttempts = 10
  excludedBiomes = [ "minecraft:ocean", "minecraft:deep_ocean", ... default set ... ]
  respectClaims = true
  maxTeleportPerMinute = 0   # 0 = rely on FTB's cooldown
```

## Where authority lives

- `/rtp` command ownership stays with FTB Essentials; this mod only substitutes
  its search. If FTB is absent, this mod does nothing (no `/rtp` to improve).
- Cooldowns: FTB's `rtp.cooldown` and `difficulty_enhancement_ftbe` remain the
  authority. This mod does not add its own unless `maxTeleportPerMinute` > 0.

## Decisions I made as the builder (check these)

- **Radius as a cap, not the border.** User said "unlimited if no performance
  impact, else limit it". Distance is not the cost (one chunk per teleport);
  unboundedness is. Chose a 32,000-block default cap.
- **Uniform-by-area picking**, matching EssentialsX, so players are not biased
  toward the center.
- **Async via `getChunkFuture`**, the Forge equivalent of EssentialsX's
  `PaperLib.getChunkAtAsync`.
- **Ocean/river exclusion on by default**, matching EssentialsX's default set.
- **No pregenerator and no location cache across restarts**; a small in-memory
  cache of a few valid spots is allowed to make repeat `/rtp` snappy, but it is
  not persisted.
- **Reflection, not compile-time deps.** Like `memfix`, this mod compiles
  against Forge alone and reaches FTB Essentials / FTB Chunks / FTB Teams by
  reflection. The pack ships SRG jars, so there is no dev jar to compile
  against; reflection is what keeps the mod pack-agnostic and loadable even if
  an FTB class moves.
- **Config authority: this mod wins for the search area.** Our
  `minRadius/maxRadius/centerX/centerZ` decide where `/rtp` may land. FTB's
  `rtp.min_distance/max_distance` are ignored once this mod intercepts. Removing
  the FTB distance keys (or leaving them) has no effect; the doc says so
  explicitly so the two configs cannot disagree.
- **Threading resolved.** In 1.20.1 Forge, `ServerChunkCache.getChunkFuture`
  completes on the server thread during chunk ticking. So the chunk-load is the
  only off-thread work; all validation (`getBlockState`, biome, heightmap, FTB
  Chunks claim lookup) and the teleport run in the future's callback on the
  server thread. This removes the doc's earlier contradiction: nothing reads
  world state off-thread and the teleport is correctly marshalled.
- **FTB pre-checks are replicated.** Because cancelling at `CommandEvent` skips
  FTB's own gates, the mod re-applies: the dimension blacklist/whitelist,
  the `ftbessentials:ignore_rtp` biome and block datapack tags, and the op
  permission-2 bypass. Our `excludedBiomes` list is merged with — not a
  replacement for — the tag-driven exclusions.
- **Lifecycle: single-flight per player.** A second `/rtp` while one search is
  pending is refused ("already searching"). Pending searches are cancelled when
  the player logs out, dies, changes dimension, or the server stops. The
  in-memory spot cache is cleared on stop. This closes the leak anti-goal.
- **Cooldown replication.** FTB arms its cooldown only inside
  `WarmupCooldownTeleporter.teleportNow`, which our interception skips, so we
  would otherwise have an unthrottled `/rtp`. This mod therefore applies the
  cooldown itself after a successful teleport (and consults FTB's
  `rtpTeleporter.checkCooldown()` first so `difficulty_enhancement_ftbe`'s
  difficulty cooldowns still gate the command). Exact mechanism is a
  plan-level call; the behaviour is: no cooldown is lost.

## User decisions (answered 2026-10-10)

1. **Cooldowns: keep current settings.** The mod replicates FTB's cooldown and
   still consults `difficulty_enhancement_ftbe`'s difficulty cooldowns, so
   behaviour is unchanged from the player's point of view. No cooldown is lost.
2. **Overworld only.** `/rtp` works in the overworld and nowhere else. In any
   other dimension the mod leaves FTB's own behaviour in place (FTB already
   blacklists `minecraft:the_end` by default; no Nether bedrock scan is added).
   Note: the user wrote "otherworld only"; read as "overworld only". Correct if
   that is wrong.
3. **Excluded biomes: expanded set.** Defaults are EssentialsX's oceans and
   rivers, plus `minecraft:deep_dark`, `minecraft:mushroom_fields`,
   `minecraft:the_void`, `minecraft:small_end_islands` and the other end biomes
   (harmless in the overworld, listed for completeness), and the pack's
   `irons_spellbooks` pocket dimension is already handled by FTB's blacklist.
   The list is user-editable.
4. **Claims: avoid other teams only.** `respectClaims = true` rejects a
   candidate chunk claimed by a *different* team. A player's own team's claims
   and ally claims are allowed. Implemented against the FTB Chunks API
   reflectively; if the API is absent the check is skipped.
