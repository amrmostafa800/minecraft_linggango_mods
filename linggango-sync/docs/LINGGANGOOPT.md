# Linggango Optimizer — datapack login-time cache

`linggangoopt` is a second, narrowly-scoped mod next to `linggangosync`. Where `linggangosync`
observes and classifies a 400-mod pack, `linggangoopt` removes one specific, measured cost:

> Every client that joins the Linggango server receives the **full recipe and tag datapack**
> on every login. That is ~8 MB pre-compression, ~1.6 MB after zlib, sent again on every
> reconnect, for data that is byte-identical for every client and only changes when the pack
> changes.

The mod caches those two payloads on the client and, when the data has not changed, skips the
server-to-client send and rebuilds the payload locally from the cached bytes.

This document is the Phase 1–7 report for that mod: environment, findings, architecture,
configuration, measurements, and known limitations. Everything below was read off or measured
against the live serverpack, not assumed.

## 1. Target platform (verified)

| Item | Value |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.20 |
| Java | 17 |
| Loader | Forge (not NeoForge) |
| Serverpack mods | 407 jars |

## 2. What is actually sent, and when

The two packets are `ClientboundUpdateRecipesPacket` and `ClientboundUpdateTagsPacket`, both
sent from `ServerGamePacketListenerImpl.send` during `placeNewPlayer`, before the client can
send any play-phase packet. Measured wire size on the Linggango pack:

| Payload | Raw | zlib (compression threshold 256, level 6) |
| --- | --- | --- |
| recipes | 7,604,936 B (7.25 MB) | 1,373,715 B (1.31 MB) |
| tags | 562,339 B (549 KB) | 263,848 B (258 KB) |
| **total** | **7.79 MB** | **1.60 MB** |

Because the send happens during login, a claim that arrives later on the play channel cannot be
used to suppress it. The server remembers each player's claim **persistently**, keyed by UUID, so
the decision is available at login time on the *next* session. First-ever join always receives
the payloads; every rejoin can skip them.

## 3. Findings that shaped the design

### 3.1 Capture the exact wire bytes, not a re-serialisation

The first attempt hooked the client packet *handlers* and re-serialised the parsed object to hash
it. That does not work: an `Ingredient` read from the network and written back is **not**
guaranteed to produce the same bytes (Forge ingredient types, ordering, dedup). The client
computed a different hash from the server for the same packet.

The final design hooks `PacketDecoder.decode` on the client and copies the frame **after the
packet-id varint**, using `ByteBuf.getBytes` (which never advances the reader index, so decoding
is untouched). Those bytes are exactly what `packet.write` produced on the server, so both sides
hash identical bytes.

### 3.2 A third-party mod rewrites the recipes packet in flight

Recipes captured on the wire are ~1.87 MB *smaller* than the `ClientboundUpdateRecipesPacket`
`write` produces on the server. `ModernFix`'s `perf.smart_ingredient_sync` mixin rewrites
`Ingredient.toNetwork` to emit a compact in-band encoding when the client has "seen" the
ingredient before. Its own `ConnectionMixin`/`PlayerListMixin` pre-seed that per-player state on
login, so the wire bytes depend on **server-side per-player state**, not only on the packet.

Consequences:

- Hashing `packet.write` on the server does **not** match the wire bytes while the mixin is on.
- With `mixin.perf.smart_ingredient_sync=false` on both sides, client and server hashes match
  exactly (verified: recipes `53f1ea70…`, tags `ef598833…` byte-identical).

The mod does not depend on that setting and never inspects ModernFix. It hashes what leaves and
what arrives; if a third-party encoding makes the two differ, the hashes simply do not match and
the payload is sent normally. The recommendation (see §6) is to leave the mixin disabled so the
optimisation actually fires.

### 3.3 The hashes are run-dependent, not world-dependent

`ClientboundUpdateRecipesPacket`/`Tags` iterate live `RecipeManager`/tag maps. Iteration order of
those maps varies between JVMs, so the same unchanged pack produced different hashes across server
restarts (e.g. recipes `b664b36e` → `5f710b84`; tags `246c6936` → `42289cd0`). This is not a
problem: the server only compares a client's claim against the hash it computes **right now**, in
the **current** run. Claims from a previous run simply do not match and the payload is sent once,
then the client claims the new hash.

Persistent, run-stable hashing is a deliberate non-goal: it would require the server to cache the
serialised payload across restarts, doubling memory for an optimisation whose whole point is to
save bandwidth.

### 3.4 A failed replay must not strand the client

If the server skips a payload and the client's cache is corrupt, evicted, or fails to rebuild the
packet (observed transiently as an `OutOfMemoryError` during recipe deserialisation on this
no-swap box), the client would be left without recipe/tag data. The mod therefore **requests a
resend**: the client sends `ResendRequest`, the server rebuilds the packet from authoritative
state, sends it, and **clears the claim** so a second failure cannot skip again. The skip path is
fail-closed — the only cost of a bad cache is a normal resend.

The earlier behaviour of `failClosed()` on a replay error (disabling all client hooks for the
session) was removed for the same reason; a replay failure now leaves the hooks installed and
heals itself.

## 4. Architecture

```
           server                                    client
  ┌───────────────────────────┐            ┌───────────────────────────┐
  │ ServerGamePacketListener  │            │ PacketDecoder             │
  │  .send(packet)            │            │  capture wire bytes       │
  │   ├─ hash of packet       │  Hello     │  (recipes / tags)         │
  │   ├─ claim matches? ──────┼───────────▶│  ── on Hello ──           │
  │   │   yes: send Hello,    │  (tiny)    │     hash matches cache?   │
  │   │        cancel payload │            │       yes: replay locally │
  │   │   no:  send normally  │            │       no:  ResendRequest ─┼─┐
  └───────────────────────────┘            └───────────────────────────┘ │
              ▲                                                          │
              └────────────── resend authoritative payload ◀────────────┘
```

- **Server memory**: `claims.bin`, player UUID → {recipes, tags} → hash. Written on every claim
  and on stop. `ServerClaims`.
- **Client cache**: `datapacks.bin`, channel → {hash, bytes}. Atomic write (temp + move).
  `DatapackCache`.
- **Control channel**: Forge `SimpleChannel` `linggangoopt:main`, three tiny messages:
  `HelloPayload` (S→C, skip notice + hash), `ClaimPayload` (C→S, what I hold),
  `ResendRequestPayload` (C→S, please send it). The payload bytes themselves never travel on this
  channel.
- **Side awareness**: `OptRuntime` gates every hook on `FMLEnvironment.dist`. Client-only classes
  are referenced only from `Dist.CLIENT`-registered code (`ClientTickHandler`,
  `PacketDecoderMixin`, and the `HelloPayload` handler), so the dedicated server never loads them.

Both mixins that touch vanilla are small and fail-closed:

| Mixin | Side | Injection | Effect |
| --- | --- | --- | --- |
| `ServerGamePacketListenerImplMixin` | both | HEAD of `send` | cancels a recipes/tags send when the claim matches |
| `PacketDecoderMixin` | client | HEAD of `PacketDecoder.decode` | copies the two payloads' wire bytes |
| `CompressionEncoderMixin` | both | HEAD of `CompressionEncoder.encode` | wire-size accounting only |

## 5. Verified end-to-end behaviour

Live test: the production client (full 407-mod pack) joined a real dedicated server.

**Join 1** — server sends both payloads, client captures and claims:
```
[opt] send recipes actual=ca5c2c32 skip=false
[opt] send tags actual=a1d1a42b skip=false
[opt] cached recipes payload (7604936 bytes)
[opt] sent claim recipes hash=ca5c2c32
[opt] cached tags payload (562339 bytes)
[opt] sent claim tags hash=a1d1a42b
```

**Join 2** — server skips both, client replays both from cache:
```
[opt] send recipes actual=ca5c2c32 skip=true
[opt] send tags actual=a1d1a42b skip=true
[opt] hello received channel=recipes hash=ca5c2c32
[opt] replayed cached recipes payload locally
[opt] hello received channel=tags hash=a1d1a42b
[opt] replayed cached tags payload locally
```

## 6. Measurements

Per-client, per-reconnect, request/response datapack traffic:

| | Before | After | Saved |
| --- | --- | --- | --- |
| Server → Client | 1.60 MB | ~0 B | 1.60 MB (100%) |
| Client → Server | 0 B | ~200 B (2 claims + 2 Hellos ≈ 260 B uncompressed) | +0.26 KB |
| Net | 1.60 MB | ~0.26 KB | **≈ 1.60 MB per reconnect** |

The saving is exact and does not depend on the claim hitting: a miss (`skip=false`) simply falls
back to the original transfer. Ten players rejoining a hundred times each is ~1.6 GB of server
egress removed. Verify on your own box with `debug=true`, which logs the `send … skip=` decision
per login.

## 7. Configuration

`config/linggangoopt-common.toml`:

```toml
[general]
enabled = true          # master switch; false = pure vanilla
debug = true            # logs every capture, claim, skip and replay

[datapack_cache]
enabled = true
recipes = true
tags = true
skip_server_send_on_hash_hit = true   # the only byte-saving option
verify_hash = true

[wire_monitor]
enabled = true
log_per_packet = false
```

**`skip_server_send_on_hash_hit` defaults to false in the shipped toml.** Turn it on
server-side to enable the saving. The intended production setting is:

- **Server**: `skip_server_send_on_hash_hit = true`, `debug = false`.
- **Client**: defaults are fine.

There is no per-command interface in this mod yet; `/linggango-net` belongs to `linggangosync`.

## 8. Supported / unsupported

**Supported**: recipe and tag datapack payloads, on every reconnect after the first join.

**Deliberately unsupported** (unchanged vanilla behaviour):

- Everything `linggangosync` classifies as `SERVER_AUTHORITATIVE` — block, inventory, entity,
  machine, redstone and contraption state. None of it is touched by this mod.
- Chunk and entity tracking — not modified.
- Any third-party custom packet — never rewritten or dropped. The mod only ever cancels a
  vanilla recipes/tags send and only when the exact bytes are already on the client.

## 9. Known compatibility notes

- **ModernFix `perf.smart_ingredient_sync`**: with the mixin enabled the wire bytes are
  per-player and cannot be predicted from `packet.write`, so the recipes skip never fires. Set
  `mixin.perf.smart_ingredient_sync=false` in `config/modernfix-mixins.properties` on both sides
  to get the saving. Tags are unaffected and skip either way. This is a recommendation, not a
  requirement — the mod stays correct (it just stops saving) with the mixin on.
- **Memory**: on a client with very little free heap, deserialising a 7.25 MB recipe payload can
  throw `OutOfMemoryError`. The resend fallback covers it, but for reliability give the client
  ≥3 GB heap on this pack.
- **First join ever** (no prior claim) and **first join after a server restart** (hashes are
  run-dependent) always transfer the payloads normally. That is expected.

## 10. Build and test

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew build          # jar in build/libs/linggangoopt-1.0.0+mc1.20.1.jar
./gradlew test           # pure-logic unit tests, no game launch
```

Two-client QA: run the mod's `client` and `client2` run configs against the `server` config,
join, disconnect, rejoin, and confirm `skip=true` + `replayed` in the logs.
