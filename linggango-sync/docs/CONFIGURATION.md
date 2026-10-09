# Configuration

The config file is `config/linggangosync.json`, created with safe defaults on first run.
It is plain JSON rather than a `ForgeConfigSpec` on purpose: the per-mod section is
open-ended, and the set of installed mods differs between the server and each client. A
schema-less map lets you add an entry for a mod this build has never heard of.

Every key is optional. Anything missing, unparsable, or misspelled falls back to the safe
default, never to an optimisation. Reload without restarting using
`/linggango-net reload` (permission level 2).

## Policies

| Policy | Meaning |
| --- | --- |
| `SERVER_AUTHORITATIVE` | The default. The server owns the value and syncs it normally. Never optimised. |
| `ON_DEMAND` | Server still owns it; delivery is deferred until a client needs it (join, GUI open, area entry). |
| `DELTA_SYNC` | Server-authoritative, sent as field-level deltas with coalescing. |
| `CACHEABLE` | Server-authoritative with a content hash, so identical payloads are skipped entirely. |
| `CLIENT_LOCAL` | Meaningful only on the client. Stored locally, never synchronised. |

An unknown or misspelled policy parses to `SERVER_AUTHORITATIVE`. That is intentional: a
typo makes the mod do *less*, never more.

## The file

```json
{
  "version": 1,
  "debug": {
    "enabled": false,
    "statsIntervalSeconds": 60,
    "packetHistoryMinutes": 5,
    "logPerPacket": false,
    "autoReportOnStop": true
  },
  "cache": {
    "maxEntries": 4096,
    "maxBytes": 67108864,
    "ttlSeconds": 604800,
    "persistToDisk": true,
    "compressOnDisk": false
  },
  "sync": {
    "player_state": "SERVER_AUTHORITATIVE",
    "inventories": "SERVER_AUTHORITATIVE",
    "containers": "SERVER_AUTHORITATIVE",
    "machines": "ON_DEMAND",
    "machine_inventory": "SERVER_AUTHORITATIVE",
    "machine_progress": "DELTA_SYNC",
    "world_blocks": "SERVER_AUTHORITATIVE",
    "block_entities": "SERVER_AUTHORITATIVE",
    "mobs": "SERVER_AUTHORITATIVE",
    "bosses": "SERVER_AUTHORITATIVE",
    "projectiles": "SERVER_AUTHORITATIVE",
    "redstone": "SERVER_AUTHORITATIVE",
    "create_contraptions": "SERVER_AUTHORITATIVE",
    "energy_networks": "DELTA_SYNC",
    "fluid_networks": "DELTA_SYNC",
    "progression": "SERVER_AUTHORITATIVE",
    "gui_data": "ON_DEMAND",
    "recipe_structures": "ON_DEMAND",
    "network_browsing": "ON_DEMAND",
    "nonessential_be": "CACHEABLE",
    "gui_state": "CLIENT_LOCAL",
    "gui_preferences": "CLIENT_LOCAL",
    "keybinds": "CLIENT_LOCAL",
    "visuals": "CLIENT_LOCAL",
    "particles": "CLIENT_LOCAL",
    "cosmetics": "CLIENT_LOCAL",
    "waypoints": "CLIENT_LOCAL",
    "map_cache": "CLIENT_LOCAL",
    "local_recipes": "CLIENT_LOCAL",
    "hud": "CLIENT_LOCAL",
    "search_history": "CLIENT_LOCAL",
    "tooltips": "CLIENT_LOCAL"
  },
  "mods": {
    "ae2": { "*": "ON_DEMAND" },
    "jei": { "*": "CLIENT_LOCAL" },
    "create": { "*": "SERVER_AUTHORITATIVE" }
  }
}
```

## `debug`

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Master switch for packet monitoring. Off means one boolean read per packet. |
| `statsIntervalSeconds` | `60` | How often the periodic summary is logged while monitoring. |
| `packetHistoryMinutes` | `5` | How many minutes of per-minute byte history to keep. |
| `logPerPacket` | `false` | Log every packet individually. Very noisy; for short investigations only. |
| `autoReportOnStop` | `true` | Print a final report when the server stops. |

Start here. Run with `enabled: true` for a normal play session, then read
`/linggango-net stats` or the shutdown report to see which mods actually dominate. Do not
enable any optimisation before you have that data.

## `cache`

Client-side only. These limits apply to the client's local cache; the server ignores them.

| Key | Default | Meaning |
| --- | --- | --- |
| `maxEntries` | `4096` | Hard cap on entries. Least-recently-used entries are evicted past this. |
| `maxBytes` | `67108864` (64 MB) | Hard cap on total cached bytes. Floored at 1 KB. |
| `ttlSeconds` | `604800` (7 days) | Entries older than this are not served and are dropped. |
| `persistToDisk` | `true` | Write the cache to `config/linggangosync-cache.bin` between runs. |
| `compressOnDisk` | `false` | Deflate the cache file. Only worth enabling if the file is large. |

Both caps are enforced, so the cache cannot grow without bound. `compressOnDisk` is off by
default because compression costs CPU on every write and only pays off for large caches.

## `sync`

Category policies, applied to every mod unless overridden. The full default set is in the
example above; the categories are grouped by what they are for:

- **Gameplay-critical** (`player_state`, `inventories`, `containers`, `world_blocks`,
  `block_entities`, `mobs`, `bosses`, `projectiles`, `redstone`, `create_contraptions`,
  `machine_inventory`, `progression`) — all `SERVER_AUTHORITATIVE`. Do not change these.
- **Bulk but deferrable** (`machines`, `gui_data`, `recipe_structures`,
  `network_browsing`) — `ON_DEMAND`.
- **Fast-changing counters** (`machine_progress`, `energy_networks`, `fluid_networks`) —
  `DELTA_SYNC`. Only the newest value matters, so coalescing is safe.
- **Purely local** (`gui_state`, `gui_preferences`, `keybinds`, `visuals`, `particles`,
  `cosmetics`, `waypoints`, `map_cache`, `local_recipes`, `hud`, `search_history`,
  `tooltips`) — `CLIENT_LOCAL`.

## `mods`

Per-mod overrides, keyed by mod id. Two forms are accepted:

```json
"mods": {
  "ars_nouveau": { "gui_data": "ON_DEMAND" },
  "create": { "*": "SERVER_AUTHORITATIVE" }
}
```

- `mods.<namespace>.<category>` sets the policy for one category in that mod.
- `mods.<namespace>.*` sets a namespace-wide default, used for any category not
  explicitly listed.

Resolution order, highest priority first:

1. `mods.<namespace>.<category>`
2. `mods.<namespace>.*`
3. `sync.<category>`
4. built-in namespace default
5. `SERVER_AUTHORITATIVE`

Note that a namespace-wide `*` beats the generic category default. That is what lets
`create: { "*": "SERVER_AUTHORITATIVE" }` act as a blanket refusal even if someone later
sets `sync.gui_data` to something more permissive.

## Suggested workflow

1. Run one normal session with `debug.enabled: true` and nothing else changed.
2. Read `/linggango-net stats` and `/linggango-net types`.
3. Pick the single largest contributor, and check `/linggango-net adapters` for a rule
   covering it and the reason it is considered safe.
4. Enable only that one optimisation.
5. Play again and compare. Keep the change only if the measurement supports it.

Do not enable everything at once; you will not be able to attribute either a regression or
an improvement.

## Commands

All require permission level 2 and are read-only except `debug`, `reset` and `reload`.

| Command | Purpose |
| --- | --- |
| `/linggango-net stats [top]` | Bytes and packets per mod, per direction, per player. |
| `/linggango-net types [top]` | Per packet-type breakdown with average size. |
| `/linggango-net packets [limit]` | The last 10 seconds of individual packets. |
| `/linggango-net cache` | Client cache size, hit rate, evictions, most recent entries. |
| `/linggango-net adapters` | Every adapter rule and its stated rationale. |
| `/linggango-net policy` | Effective category policies and registered descriptors. |
| `/linggango-net debug on\|off` | Turn monitoring on or off at runtime. |
| `/linggango-net reset` | Clear collected statistics. |
| `/linggango-net reload` | Re-read `config/linggangosync.json`. |
| `/linggango-net export <file>` | Write a full statistics dump to `config/<file>`. |
