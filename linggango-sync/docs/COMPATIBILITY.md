# Linggango Sync — Phase 1 compatibility report

Everything below was read off the actual serverpack, not assumed. The serverpack archive
was extracted locally and inspected directly.

## 1. Target platform (verified)

| Item | Value | Where it was read from |
| --- | --- | --- |
| Minecraft | **1.20.1** | `mods/` jar names, `libraries/net/minecraftforge/forge/1.20.1-47.4.20/` |
| Forge | **47.4.20** | `libraries/net/minecraftforge/forge/1.20.1-47.4.20/`, `run.sh` |
| Java | **17** | `README.txt` ("Java 17"), `unix_args.txt` |
| Loader type | Forge (not NeoForge) | `libraries/net/minecraftforge/`, `run.sh` |

`README.txt` says "Forge 47.4.10" in prose, but the shipped libraries and `run.sh` both
reference `1.20.1-47.4.20`. The actual installed version is **47.4.20**, and the build
targets that. Forge 47.x is patch-compatible, so 47.4.10 would also load the jar.

## 2. Mod count

407 jars in `mods/`. 393 of them contain at least one networking-related class. That count
is why the design starts with measurement rather than optimisation: guessing which of 393
mods is responsible for the traffic would be guesswork.

## 3. Mods by networking class count

Counted by scanning each jar for classes referencing `SimpleChannel`, `PacketDistributor`,
`CustomPayload` or `NetworkRegistry`. This is a proxy for "this mod sends custom packets",
not a measurement of volume. Volume is what `/linggango-net stats` is for.

| Mod | Networking classes | Mod | Networking classes |
| --- | --- | --- | --- |
| macabre | 226 | cofh_core | 54 |
| create | 153 | thermal_dynamics | 51 |
| terramity | 152 | jei | 48 |
| linggango_tweaks | 109 | supplementaries | 47 |
| ImmersiveEngineering | 109 | alexscaves | 46 |
| born_in_chaos | 105 | bossesrise | 41 |
| hexerei | 97 | ExtendedAE | 41 |
| goety | 95 | legendarymonsters | 40 |
| Armageddon | 94 | l2backpack | 39 |
| appliedenergistics2 | 93 | starcatcher | 35 |
| justdirethings | 89 | toms_storage | 34 |
| Mekanism | 81 | alexsmobs | 34 |
| irons_spellbooks | 65 | polymorph | 33 |
| sophisticatedcore | 62 | modular-routers | 32 |
| ars_nouveau | 60 | mahoutsukai | 32 |
| Quark | 59 | curios | 32 |
| BloodMagic | 59 | easy-villagers | 31 |
| L_Enders_Cataclysm | 55 | TerraCurio | 31 |

Two things stand out and shaped the adapter list:

1. **`create` (153) and `linggango_tweaks` (109) are the pack's own customisations.** Any
   optimisation that touches Create's contraption packets risks the pack's own tweaks.
   Both are therefore treated as fully authoritative, and Create is only *observed*.
2. **Storage networks are heavily represented** — AE2 (93), ExtendedAE (41), toms_storage
   (34), sophisticatedcore (62), sophisticatedstorage (28), sophisticatedbackpacks (23),
   storagedrawers, refinedstorage. These are the mods most likely to push large NBT blobs
   on a continuous basis, which is exactly the Category 2 "sync on demand" case.

## 4. Which data is genuinely server-authoritative

The mod's defaults follow this list. Nothing here is ever made client-authoritative.

| System | Verdict | Reason |
| --- | --- | --- |
| Player position / rotation | server | Vanilla movement, client predicts but server corrects. |
| Health, hunger, effects | server | Decide whether a player survives. |
| Inventories, container contents | server | Decide what a player owns. |
| Machine inventories, processing state | server | Observable and interactable by other players. |
| World blocks, block entities | server | Shared world state. |
| Mob / boss / projectile state | server | Two players must see the same fight. |
| Redstone | server | Same circuit must produce the same output for everyone. |
| Create contraptions | server | Contraption position and contents affect collisions and items. |
| AE2 / RS storage contents, crafting jobs | server | Decide what a crafting job consumes and produces. |
| Progression, quest state | server | Unlocks server content. |

## 5. Which data is safe to defer or keep local

| System | Verdict | Reason |
| --- | --- | --- |
| ME terminal browsing list | on demand | Read-only projection of authoritative contents. |
| Machine energy/fluid/gas levels | delta | Only the newest value matters; coalescing changes nothing a player can act on. |
| JEI / EMI recipe index | client local | Derived from the installed jars, identical on every client. |
| Xaero / JourneyMap map data | client local | Personal exploration record, never read by the server. |
| GUI state, sorting, filters, HUD | client local | Presentation only. |
| Waypoints (Xaero) | client local | Cosmetic unless a mod teleports on them, which the server still validates. |
| Cosmetics, particles, camera | client local | No server outcome. |

## 6. Existing networking APIs found

- **Forge `SimpleChannel`** (`net.minecraftforge.network.NetworkRegistry`,
  `SimpleChannel`, `PacketDistributor`) — the dominant API in this pack.
- **Vanilla `ClientboundCustomPayloadPacket` / `ServerboundCustomPayloadPacket`** — every
  `SimpleChannel` message ends up in one of these, with the channel name in the payload.
- **`ICustomPacket`** — Forge's interface for reading the channel name and login index off
  a custom payload packet without decoding it.
- **`Connection#getPacketListener`** — reachable from the Netty pipeline via the
  `packet_handler` channel handler, which is how the monitor attributes traffic to a player.
- **Capabilities and `IExtensionPoint`** — used by several mods for optional cross-mod
  integration. Not touched by this mod.

Because every modded message travels inside a vanilla custom payload, a single observation
point on `PacketEncoder` sees all of them, with the owning channel name available. That is
the hook the monitor uses. It is observation only — see §8.

## 7. Which mods synchronise large structures

Ranked by how likely they are to send large, repetitive payloads:

1. **AE2 / ExtendedAE** — terminal item lists. Large, sent repeatedly, and a pure projection.
2. **Mekanism** — machine NBT including inventories and energy. Frequent, partly derivable.
3. **Create** — contraption and train data, plus the pack's own `linggango_tweaks`. Large,
   but genuinely gameplay-affecting.
4. **sophisticatedcore / storage / backpacks** — upgrade and contents NBT.
5. **toms_storage** — network inventory lists.
6. **ars_nouveau** — spell and mana state; the mana bar is a HUD element.
7. **ImmersiveEngineering** — multiblock and wire network state.
8. **thermal_dynamics / cofh_core** — energy and fluid networks.

The first two are where on-demand and delta sync have the most to gain. Create is where
they are most dangerous, and is deliberately left alone.

## 8. What this mod deliberately does not do

- It does **not** cancel, rewrite, reorder, delay or drop any packet belonging to another
  mod. Packet ordering, acknowledgements, capabilities and login handshakes are all things
  a mod may depend on, and rewriting them without a proof of safety would break the pack.
- It does **not** disable synchronisation globally. Every default in `DataClassifier` is
  `SERVER_AUTHORITATIVE` except for categories that provably cannot affect a server outcome.
- It does **not** claim any optimisation works. Adapters declare intent and rationale; the
  operator's config decides what is actually enabled, and the monitor measures the result.

## 9. Failure behaviour

The system fails closed. If a mod has no adapter, if a data type is unregistered, if a
config value is unparsable, if a payload fails its checksum, if a cache file is corrupt or
truncated, or if a client's cached hash does not match — the result is a normal,
unoptimised synchronisation. No optimisation is ever applied on the basis of missing
information.
