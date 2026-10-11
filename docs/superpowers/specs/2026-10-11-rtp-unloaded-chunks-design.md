# /rtp: teleport into unloaded chunks (generate new terrain)

Date: 2026-10-11
Mod: `linggango-rtp` 1.3.0
Requirement (user): "rtp teleport to unloaded chunks only" / "i mean generate new chunks".

## Behaviour

`/rtp` must land the player in a chunk that is **not currently loaded in memory**.
Because the destination is not loaded, it is generated when the player's ticket
brings it to FULL — i.e. RTP reaches **new terrain**, which is the point of a
random teleport.

This is the opposite of the 1.2.0 experiment (`loadedChunksOnly`), which only
landed inside the area already loaded around players and therefore teleported
the player only within terrain they could already see. That path is removed.

## Config

- `unloadedChunksOnly` (default `true`): reject any candidate whose chunk is
  already loaded. This is what makes RTP reach fresh terrain.
- `existingChunksOnly` (default now `false`): the on-disk gate. Off, the search
  does not care whether the chunk exists on disk, so a never-generated candidate
  is accepted and generated on arrival. Turn it on to restrict RTP to
  already-generated terrain (no generation, no stall).

## Search flow

1. `start` runs on the server thread. It snapshots the loaded chunk set via
   `LoadedChunks.positions(level.getChunkSource())` — `ChunkMap.getChunks()`
   (widened by the access transformer), keeping only holders whose FULL chunk is
   present. The snapshot is captured on the server thread; the worker never reads
   world state.
2. The worker (`RtpSearch.attempt`) draws a candidate uniformly by area from the
   annulus (`CandidatePicker`). If `unloadedChunksOnly` and the candidate's chunk
   is in the snapshot, it is rejected and the next attempt runs. If the on-disk
   gate is enabled and the neighbourhood is missing, it is rejected too.
3. `requestChunk` runs on the server thread and re-checks `getChunkNow` — a chunk
   that loaded since the snapshot (a player walked near it) is rejected, so the
   landing stays fresh.
4. The chunk is requested at FULL and validated (biome, claims, safe landing),
   then the player is teleported via FTB.

## Cost and trade-off

Generating terrain on arrival is expensive. Measured on the 407-mod local server:
a landing at chunk `(18,52)` (39x44 chunks from the player, outside the 529
loaded chunks) generated the area and caused a **3.6 s / 72-tick stall**. That is
inherent: the player's ticket promotes a view-distance area of chunks from
nothing to FULL in one go. Mitigations if the stall matters:

- Lower the server `view-distance` (fewer chunks per landing).
- Set `existingChunksOnly = true` to fall back to no-generation landings.

## Why the picker is pure

`UnloadedCandidatePicker` takes the loaded set and the annulus and returns
candidates with no Minecraft classes, so the "avoid loaded" rule is unit-tested
(`UnloadedCandidatePickerTest`) without a server. `LoadedChunks` is the only
class that touches chunk internals.
