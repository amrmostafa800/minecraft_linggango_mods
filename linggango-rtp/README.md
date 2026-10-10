# Linggango RTP

A server-side Forge mod for the **Linggango 6.6.5b3hf** modpack (Minecraft 1.20.1, Forge 47.4.20,
Java 17). It replaces FTB Essentials' synchronous `/rtp` location search with an EssentialsX-style
asynchronous one, so a random teleport can reach terrain that has never been generated without
stalling the server thread.

> **Status:** Task 1 scaffold only. This jar currently logs `[rtp] loaded` at startup and does
> nothing else. The asynchronous search lands in later tasks.

## Intent

FTB Essentials' `/rtp` picks a random candidate and then blocks the calling (server) thread while
the chunk generates, once per candidate up to `max_tries` times. On the Linggango pack that means
players can only ever be placed in terrain that is already generated, and each attempt in fresh
terrain freezes every other player for the duration of the generation.

This mod owns `/rtp` and nothing else. It picks candidates across a bounded spread, loads each
chunk **off the main thread**, validates the landing on the server thread when the chunk is ready,
retries a bounded number of times, and then delegates the actual teleport back to FTB so cooldowns,
warmups, teleport history and dimension rules are unchanged.

## Non-goals

- Replacing FTB's other teleport commands (`/home`, `/back`, `/tpa`, `/warp`, `/spawn`).
- Reaching the full 30M world border.
- Touching other dimensions: `/rtp` may only land in the overworld; elsewhere FTB's behaviour
  stands untouched.
- Any main-thread terrain generation.

## How it works (design)

- Intercepts `/rtp` at Forge's `CommandEvent`, cancels FTB's synchronous implementation, and runs
  its own search.
- Picks a uniformly-by-area random point within a configured radius (default 32,000 blocks) of a
  configurable center (default world origin), clamped to the world border.
- Loads the candidate chunk through `ServerChunkCache.getChunkFuture` — never blocking the server
  tick on generation.
- Validates the landing on the server thread (solid, safe, open ground; not an excluded biome such
  as ocean or river), retrying up to N times.
- Hands the found position to FTB's own `rtpTeleporter.teleport(...)` so FTB still applies the
  cooldown, dimension blacklist, warm-up and teleport history.
- Fails gracefully: if no valid spot is found, tells the player and does not teleport.

All FTB access is reflective, so the mod compiles against Forge alone (no mod dependencies in
`build.gradle`) and does nothing if FTB Essentials is absent.

## Install

1. Drop `linggango_rtp-1.0.0.jar` into the server's `mods/` folder.
2. Restart. No client mod is required.

FTB Essentials is a soft dependency: it is not required to load the mod, but the mod's behaviour
only applies where FTB Essentials is present.

## Building from source

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build --no-daemon --console=plain
# artifact: build/libs/linggango_rtp-1.0.0.jar
```

The first build downloads and decompiles Minecraft through ForgeGradle and takes a few minutes.

## References

- Design: `docs/superpowers/specs/2026-10-10-linggango-rtp-design.md`
- Plan: `docs/superpowers/plans/2026-10-10-linggango-rtp.md`

---

_This mod and its documentation were created by an AI agent (OpenHands) on behalf of the
repository owner. Review the code before deploying it to a production server._
