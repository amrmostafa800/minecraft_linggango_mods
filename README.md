# Linggango Sync

A networking optimisation layer for the **Linggango** private Forge serverpack. It measures
where multiplayer traffic actually goes, then lets you make specific, proven-safe pieces of
synchronisation local, deferred, or delta-encoded — without touching anything that decides
a gameplay outcome.

It is inspired by Terraria's approach: the server stays authoritative over gameplay, while
presentation, caches, and reproducible client-side data stay on the client.

## Status

Built and verified against the real serverpack. Version **1.0.0+mc1.20.1**.

| Check | Result |
| --- | --- |
| Unit tests | **78 / 78 pass** |
| Build | `gradle build` succeeds |
| Loads in the actual 407-mod serverpack | Yes |
| Mixin applies to `PacketEncoder` | Yes — confirmed in `logs/debug.log` |
| Real packet captured and attributed | Yes — 127 B `Vanilla` `S->C` |
| Operators' commands work live | Yes — `stats`, `adapters`, `policy` verified |

## Important honesty note on benchmarks

The task asked for before/after bandwidth measurements. **The "before" numbers are real and
measured; there is no credible "after" number yet, and I am not going to invent one.**

- What is proven by measurement: the **delta codec** reduces a typical machine payload by
  **98.6%** (3162 B → 45 B), and **coalescing** reduces 2000 rapid updates to 200 packets
  (**90.0%**). These are real, reproducible, enforced by passing tests.
- What is **not** proven: end-to-end bandwidth savings on your live server. That depends on
  which mods actually dominate your traffic, which this mod is explicitly designed to
  measure first.

The intended workflow is: run with `debug.enabled: true`, read `/linggango-net stats`, and
then enable only the optimisations the data supports. See `docs/CONFIGURATION.md`.

## Quick start

1. Build (below) or use the prebuilt jar.
2. Drop the jar into `mods/` on **both** the server and every client.
3. Start the server once. It writes `config/linggangosync.json` with safe defaults.
4. Set `debug.enabled: true` and play a normal session.
5. Run `/linggango-net stats` (operator only) to see where the bytes go.
6. Change **one** policy, play again, and compare.

## Build instructions

Requires JDK 17 and Gradle 8.5+.

```bash
export JAVA_HOME=/path/to/jdk-17
gradle build
```

Output: `build/libs/linggangosync-1.0.0+mc1.20.1.jar`

Run the tests only:

```bash
gradle test
```

The benchmark tests print their numbers to the console, so run with `--info` if your Gradle
version hides test stdout.

## What it actually does

The mod has four independently-useful parts.

### 1. Packet monitor (works today, zero risk)

A single `@Inject` on `PacketEncoder#encode` measures **every** outbound packet at the point
its real wire size is known — after compression and encryption, not an estimate. Each packet
is attributed to a mod by its `SimpleChannel` name (via Forge's `ICustomPacket`) and to a
player via the connection's packet listener.

It is **observation only**. It never modifies, drops, reorders or delays a packet, and it
always calls through to the original encoder. When monitoring is off, the cost is one
boolean read per packet.

This is the part to trust first: it tells you the truth about your server even if you never
enable a single optimisation.

### 2. Client cache (implemented and unit-tested)

An LRU cache with both an entry cap and a byte cap, TTL expiry, optional disk persistence,
and defensive copying on both write and read (a caller cannot mutate the cache through the
array it passed in, or the array it got back). Corrupt or truncated files on disk are
discarded rather than trusted.

Addresses the task's `get` / `put` / `invalidate` / `hasVersion` / `clear` / `clearExpired`
requirement, plus `evictions`, `expired` and `rejected` counters surfaced via
`/linggango-net cache`.

### 3. Delta + coalescing codec (implemented and benchmarked)

Field-level deltas with a schema hash, so a version mismatch causes a full snapshot rather
than a misread. A coalescing window collapses rapid updates to the newest value **only**
when intermediate states are provably irrelevant; a change to a *different* field flushes
immediately instead of being swallowed. Corrupt, truncated, mis-schema'd and
uncoercible payloads are all rejected, never guessed at.

### 4. Adapter registry + policy config (implemented, wired to commands)

Ten adapters declaring **60 data types**, each carrying a written rationale. Every adapter
rule is either `safe(...)` with a reason it cannot change a gameplay outcome, or
`refused(...)` with a reason it must stay authoritative. `/linggango-net adapters` prints
both, so the safety argument is auditable rather than implicit.

The default for anything unregistered is `SERVER_AUTHORITATIVE`.

## Supported integrations

Every entry below is a *declaration with a rationale*. Nothing is optimised unless your
config says so.

| Adapter | Optimisable | Deliberately refused |
| --- | --- | --- |
| **vanilla** | waypoints, particles | player_state, inventories, containers, world_blocks, block_entities, mobs, projectiles, redstone, xp, damage |
| **AE2** | network_browsing (on demand), network_state (delta) | storage_contents, crafting_cpu, grid_energy |
| **Create** | *(nothing)* | contraptions, contraption_storage, trains, glue, elevators, kinetic_network, ponder |
| **Mekanism** | energy_networks, fluid_networks (delta) | machine_progress, machine_inventory, fission_reactor |
| **Ars Nouveau** | gui_data (on demand) | spell_effects, mana, familiar_state, persistent_data |
| **sophisticatedcore** | gui_data (on demand) | storage_contents, upgrade_effects |
| **toms_storage / storagedrawers / refinedstorage** | network_browsing (on demand) | inventory, crafting |
| **JEI / EMI / GuideME** | local_recipes, gui_preferences, gui_state | recipeessentials:server_recipes |
| **Xaero** | map_cache, waypoints | waystone_destinations |
| **generic (`*`)** | gui_state, gui_preferences, visuals, particles, cosmetics, tooltips, keybinds | unknown |

Create gets **no** optimisations on purpose. It has 153 networking classes and the pack
ships its own `linggango_tweaks` Create integration; contraption state affects collisions
and item movement. The risk is not worth the bandwidth.

## Intentionally unsupported systems

These are **not** optimised, and the mod refuses to pretend otherwise:

- **Arbitrary packet rewriting.** No mod's packets are cancelled, rewritten, reordered or
  batched unless an explicit adapter proves it safe. Packet ordering, acknowledgements,
  capabilities and login handshakes are all things mods depend on.
- **Global synchronisation disable.** There is no "turn off syncing" switch, because that
  breaks multiplayer correctness. Only per-category policy exists.
- **Create contraptions and trains.** See above.
- **AE2/RS stored contents and crafting jobs.** These decide what you own.
- **Anything not registered.** Fails closed to `SERVER_AUTHORITATIVE`.

## Known compatibility problems and discoveries

1. **`connectivity` already mixins `PacketEncoder`.** The serverpack ships `connectivity`,
   which has both `PacketEncoderMixin` and `networkstats.NettyPacketEncoderMixin` on the
   same target class. Verified in `logs/debug.log` that all three compose without error —
   Mixin supports multiple independent `@Inject` handlers at the same point. If you ever see
   a mixin conflict, disabling `connectivity`'s stats feature is the first thing to try.
2. **`Immersive Optimization` is also present.** It overlaps conceptually with this mod. No
   conflict observed, but it is a second optimisation layer; measure with only one enabled
   at a time if you are chasing an attribution question.
3. **402 mods, not just the "Linggango mods".** The pack pulls in a large dependency tree.
   The 393 mods with networking classes (see `docs/COMPATIBILITY.md`) mean packet
   attribution by class name alone is unreliable — that is why attribution prefers the
   `SimpleChannel` name and falls back to class name only when no channel exists.
4. **`README.txt` says Forge 47.4.10; the jar and `run.sh` say 47.4.20.** The build targets
   47.4.20. Forge 47.x patches are compatible, so 47.4.10 also loads it.
5. **Client and server mod sets may differ.** Adapters whose target mod is absent report
   `isAvailable() == false` and are skipped, so a mismatched set still starts cleanly.
6. **`/linggango-net reload` cannot change cache limits for the running client.** Cache
   sizing is applied at client startup; the command says so rather than silently ignoring it.

## Documentation

- [`docs/COMPATIBILITY.md`](docs/COMPATIBILITY.md) — the Phase 1 environment analysis:
  verified versions, mod-by-mod networking counts, and which data is authoritative.
- [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) — every config key, policy semantics,
  resolution order, and the recommended measurement workflow.

## Extending it

Third-party mods can register without touching the core:

```java
SyncRegistry.register(
    "mymod",              // namespace
    "gui_data",           // data type
    SyncPolicy.ON_DEMAND, // policy
    mySerializer,         // may be null while declaring only
    myValidator);         // use SyncValidator.rejectAll() if the client never sends it
```

A descriptor is a *declaration*. It only becomes `wireReady()` once it has a serializer, and
an unready descriptor is never optimised. The operator's config, not the registration,
decides the effective policy.

Implement `SyncAdapter` to declare a whole mod's rules at once, with rationales, and
`Adapters.add(...)` to install it.

## Design rules this code follows

- **Fail closed.** Missing adapter, unregistered type, unparsable config, bad checksum,
  corrupt cache, hash mismatch — all resolve to normal synchronisation.
- **Never optimise silently.** Every `safe(...)` rule states why it cannot change a server
  outcome; every `refused(...)` rule states why it must stay authoritative.
- **Observe before optimising.** The monitor exists so you can act on data, not guesses.
- **Mixins only where necessary.** Exactly one, isolated to `mixin/`, observation-only.
- **No unbounded growth.** Cache entry and byte caps, plus TTL, are enforced.
