# Design: generic consecutive-duplicate packet suppression

Date: 2026-10-11
Status: proposed
Module: `linggango-sync` (mod id `linggangosync`, MC 1.20.1 / Forge 47.4.20)
Commit base: `fb711f7`

## Intent and why

The server is CPU/network bound, and `linggango-sync` currently only *measures* traffic.
The user's goal is Terraria-style: send each player only what that player needs. The first
optimisation is the cheapest, broadest, lowest-risk cut that actually moves the numbers.

Reconnaissance on the live local pack (407 mods, one idle player, 18m40s window) found the
recurring cost is not join data (chunks/registries are one-shot) but small custom payloads
re-sent to players who already have the identical bytes:

| Mod | Idle packets | Mechanism |
| --- | --- | --- |
| soulsweapons | 7,373 | `ModEvents.onPlayerTick` (every tick) -> `TargetPostureData.resetValues` -> `TargetPostureSyncS2C`, sent even when the value is unchanged |
| Goety | 2,117 | `EntityUpdatePacket` (entity NBT) broadcast on a schedule |
| l2tabs | 1,053 | mixin on `ServerEntity.sendDirtyEntityData` -> `SyncAttributeToClient` to every tracking player |

All three re-send byte-identical payloads. Suppressing a payload that is identical to the
one most recently sent to the same player on the same channel removes that cost.

## Goal

Drop consecutive byte-identical outbound custom-payload packets, per player, per channel,
at the single outbound funnel, so the server never encodes/sends a payload the client
already has.

## Non-goals

- Not per-mod patches (generic only).
- Not delta encoding, interest management, or on-demand delivery (later phases).
- Not touching one-shot packets (join data, registry sync) or `Vanilla` packets. The first
  cut targets Forge `ICustomPacket` (mod custom payloads) only.

## Anti-goals (would be a failure)

- Breaking any connection (e.g. emitting a zero-length frame, or dropping a packet that
  carries a one-shot effect).
- Suppressing a packet whose repetition is meaningful and *not* a resend of identical
  state.
- Adding measurable CPU cost when the feature is disabled.

## Constraints

- Must not emit a zero-length frame. The vanilla encoder writes the packet-id byte, so an
  empty buffer would produce a length-1 frame that a client tries to decode -> disconnect.
  Therefore suppression is done by **not calling through** to the encoder (cancel the
  encode) at the funnel, before any bytes are written.
- `expect=1` semantics: the injection must fail loudly if the target method disappears.
- Default OFF, because suppression is a behavioural change.
- One boolean/flag read on the hot path when disabled.

## What the user decided

- Generic consecutive-duplicate suppression, not a soulsweapons-specific fix.
- "Ignore refuse-anything-risky": correctness of a deliberately-repeated identical packet
  is an accepted risk on a friends-only server.
- Report-first is already shipped; this is the first optimisation on top of it.

## The design

### Suppression point

`net.minecraft.network.Connection` has one outbound funnel:

```
send(Packet)                     -> send(Packet, PacketSendListener)
send(Packet, PacketSendListener) -> sendPacket(Packet, PacketSendListener)   // private
sendPacket(...)                  -> channel.writeAndFlush(packet)
```

Inject at `@At("HEAD")` of the **private** `sendPacket` and cancel (`ci.cancel()`) when the
packet is a suppressed duplicate. Cancelling here means no bytes are written, so no empty
frame. Injecting the private method (rather than the public `send`) avoids double-counting
and catches every caller.

Method descriptor (official names; the mixin refmap translates them to SRG at runtime):

```
sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V
```

### What counts as a duplicate

Only packets implementing Forge's `ICustomPacket<T>` (all mod custom payloads) are
candidates. The identity of a payload is:

```
key = (channelName, messageIndex, payloadBytes)
```

- `channelName` = `ICustomPacket.getName()`
- `messageIndex` = `ICustomPacket.getIndex()`
- `payloadBytes` = the bytes of `ICustomPacket.getInternalData()` (readable bytes only)

For each connection we remember the last key **per channel**, as a 64-bit hash (FNV-1a over
channel name + index + payload) plus the payload length. If the new packet's hash and
length equal the stored one for that channel, it is suppressed.

This suppresses only a packet identical to the *immediately preceding* packet on that
channel, so a sequence A, B, A, B is untouched — only true back-to-back repeats are cut.

### Bounded memory

State is per connection, keyed by the `Connection` object (already available), storing a
small map of channel -> (hash, length). On disconnect, state is dropped (a
`Connection`-identity map with a hard cap, cleared like `PlayerNames`). No unbounded growth.

### Safety / fail-closed

- Non-`ICustomPacket` packets: never suppressed.
- If reading the payload throws: send normally (fail open to sending, never to dropping).
- Only `SERVER_TO_CLIENT`? The funnel is shared. Suppression must apply only to
  clientbound traffic (the direction the server encodes). Direction is taken from the
  connection's flow (`Connection` has a `getDirection()`/`PacketFlow`); only
  `CLIENTBOUND` is considered.
- Config toggle default `false`; when off, the injected method returns after one flag read.

### Measurement

Reuse `NetworkMonitor`: add a `suppressed` counter (packets and bytes) surfaced by
`/linggango-net stats` and the periodic report, so the win is visible and attributable.

## Files

- New: `net/DuplicateSuppressor.java` (pure logic: key hashing, per-connection state,
  `shouldSuppress(connection, packet)`), unit-tested.
- New: `mixin/ConnectionSendMixin.java` (the injection; thin, delegates to the above).
- Edit: `config/SyncConfig.java` (new `sync.suppressDuplicates` boolean, default false).
- Edit: `net/NetworkMonitor.java` (+ suppressed counters, report lines).
- Edit: `linggangosync.mixins.json` (register the new mixin).
- Edit: `docs/CONFIGURATION.md`, `README.md` (document the feature and its risk).
- Tests: `net/DuplicateSuppressorTest.java`.

## Testing

- Unit (TDD): identical consecutive payload -> suppressed; differing payload -> sent;
  A,B,A,B -> nothing suppressed; different channels tracked independently; non-custom
  packet -> never suppressed; per-connection isolation; state cleared on forget.
- Integration: load in the local 407-mod server, enable the feature, run an idle bot,
  confirm soulsweapons/l2tabs packet counts fall and the client stays connected.
- Regression: full suite green; disabled-by-default path unchanged.

## Resolved during the builder check

A fresh-eyes pass raised four points; all four were checked against the 1.20.1 / 47.4.20
sources and resolved here.

1. **Vanilla/Forge payloads could be caught.** Checked: in 1.20.1 `ClientboundCustomPayloadPacket`
   and `ClientboundCustomQueryPacket` implement only `Packet`, not `ICustomPacket`, so the
   literal `instanceof ICustomPacket` test does not catch them. Forge's own registry/config
   sync may still implement it. Resolution: restrict to the **PLAY phase** and skip the
   `minecraft` and `forge` namespaces, on top of the immediate-repeat rule (one-shot join
   packets are never consecutive repeats anyway). This is belt-and-braces against anti-goal 1.
2. **Direction accessor.** There is no `getDirection()`. The real pair is
   `getReceiving()` (`m_178313_`) and `getSending()` (`m_178314_`). The server's
   `Connection` is constructed with `PacketFlow.SERVERBOUND`, so **`getSending()` is the
   one that yields `CLIENTBOUND`**. Use that.
3. **Config key location.** `SyncConfig.apply` parses every `sync.*` primitive as a
   `SyncPolicy` into `categories`, so a boolean there would be silently swallowed.
   Resolution: add a new top-level `optimisation` section with `suppressDuplicates`
   (default `false`), read by its own `bool(...)` call.
4. **Skipped bookkeeping in `sendPacket`.** Cancelling at `HEAD` skips the outbound packet
   counter, the protocol-switch/autoRead handling, and any attached `PacketSendListener`.
   Resolution: **skip suppression when a `PacketSendListener` is non-null** (safety first).
   The counter skip is accepted — a suppressed packet was not sent, so not counting it is
   correct. The protocol switch cannot apply, because a duplicate is by definition in the
   same phase as the packet it repeats.

## Left to the builder

- Exact FNV constants and whether to store a second "last" slot per channel (for A,B,A,B)
  is the builder's call; the design's requirement is only "immediate repeat".
- Whether the counter lives in `NetworkMonitor` or a separate `SuppressionStats` is the
  builder's call.
