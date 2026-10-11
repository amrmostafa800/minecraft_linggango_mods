# Deploying Linggango RTP

Operator record for how `linggango_rtp` was installed on the live Linggango server
and how to verify, tune, and roll it back. Random teleport is served by this mod;
the old `linggango-memfix` `/rtp` guard and pregenerator were removed in memfix
1.1.0 (they made `/rtp` refuse to search reliable terrain — see
`linggango-memfix/README.md`, *History*).

## Artifact

| | |
| --- | --- |
| File | `build/libs/linggango_rtp-1.0.0.jar` |
| Size | 24,288 bytes |
| SHA-256 | `db2fcdcce22ac703113e8d2419c049f2ff93196999809112a03333055fe01197` |

The jar is **not committed to git** (`build/` is gitignored). Build it with:

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
cd linggango-rtp
sh gradlew clean build --no-daemon --console=plain
sha256sum build/libs/linggango_rtp-1.0.0.jar
```

The build is not bit-reproducible (jar entry timestamps), so the SHA-256 above
identifies the specific artifact that was deployed, not "the only correct build".
Treat the hash as a record, and re-record it here after any rebuild you deploy
rather than as a hard gate.

## Target

| | |
| --- | --- |
| Protocol | SFTP |
| Host | `fr-zero.acorehosting.com` |
| Port | `2022` |
| User | `amrmostafa800.678cb0d5` |
| Root | the login directory *is* the server root; `mods/` and `config/` are relative to it |

Credentials are not stored in this repo. The password was supplied by the
operator; it is not recorded anywhere in the repository.

## What was deployed (2026-10-11)

Server was stopped at the time (idle ~5.5 h), so this was a clean window.

| Action | Result |
| --- | --- |
| Backup | `backups/deploy-20261011-012520/` (memfix-1.0.0.jar + memfix-common.toml) |
| New mod | uploaded `mods/linggango_rtp-1.0.0.jar` |
| memfix | `mods/memfix-1.0.0.jar` removed, `memfix-1.1.0.jar` uploaded |
| memfix config | `[memfix.rtp]` table removed (4196 → 2945 bytes); sweep settings untouched |

The removed memfix table held `guard = true` and `pregenerate = true`. memfix
1.1.0 no longer defines those keys, so leaving the table would only produce
"unknown config key" noise; it was removed outright.

## Verify after the next start

Watch `logs/latest.log` for:

- `[rtp] loaded` — the mod loaded.
- **No** `[memfix] /rtp guard installed` line — the old guard is gone.
- **No** `[memfix] pregen:` lines — the pregenerator is gone.
- The memfix startup line should now be only `[memfix] loaded 36 leak targets`.

Then, as an **op in the overworld**, run `/rtp` several times:

- Each run should report a found location and land in terrain. If it reports
  "could not find a location", that is a bug in this mod — record the log lines
  and roll back.
- Watch for `ServerHangWatchdog`. There must be **no** long-tick report during or
  after a search; a report means the search is stalling the server thread.
- Run `/rtp` twice quickly; the second must produce FTB's cooldown message
  (delegation to FTB is intact).
- Run `/back` after an `/rtp`; it must return to the pre-`/rtp` position (FTB's
  teleport history still ran, i.e. the mod delegated rather than moving the
  player itself).

## Config

`config/linggango-rtp-common.toml` (created on first load):

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch; when false, `/rtp` is FTB's unchanged |
| `centerX` / `centerZ` | `0` / `0` | Search area centre |
| `minRadius` | `0` | Minimum distance from centre |
| `maxRadius` | `32000` | Maximum distance from centre |
| `findAttempts` | `10` | Candidates to try before giving up |
| `respectClaims` | `true` | Skip candidates inside an FTB Chunks claim |
| `excludedBiomes` | oceans, rivers, deep dark, mushroom fields, void, outer End | Biomes to avoid |

`minRadius`/`maxRadius` are this mod's own bounds. FTB's `rtp.min_distance` /
`rtp.max_distance` (currently `500` / `2048`) are not consulted by the search —
if you want a shorter reach, lower `maxRadius` here.

## Rollback

1. Remove the mod:
   ```bash
   rm mods/linggango_rtp-1.0.0.jar
   ```
2. Restore memfix 1.0.0 (optional — 1.1.0 is a drop-in with the guard removed):
   ```bash
   rm mods/memfix-1.1.0.jar
   cp backups/deploy-20261011-012520/memfix-1.0.0.jar mods/memfix-1.0.0.jar
   cp backups/deploy-20261011-012520/memfix-common.toml config/memfix-common.toml
   ```
3. Restart. `/rtp` returns to FTB Essentials' built-in behaviour.

Do not delete `backups/deploy-20261011-012520/` until the deploy is confirmed
stable.

## Honest status

This mod was built, unit-tested (21 tests) and statically inspected, but has
**never been executed against a live client** — the build environment has no
Minecraft client, so the interception, the async chunk load, the landing
validation and the FTB delegation have not run in-game. The verify section above
is the acceptance test. If any of it fails, roll back.
