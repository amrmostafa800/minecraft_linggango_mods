# Deploying Linggango RTP

Operator record for how `linggango_rtp` was installed on the live Linggango server
and how to verify, tune, and roll it back. Random teleport is served by this mod;
the old `linggango-memfix` `/rtp` guard and pregenerator were removed in memfix
1.1.0 (they made `/rtp` refuse to search reliable terrain - see
`linggango-memfix/README.md`, *History*).

## Artifact

| | |
| --- | --- |
| File | `build/libs/linggango_rtp-1.1.0.jar` |
| Size | 29,982 bytes |
| SHA-256 | `dc7cdf8c454bc7859ed491e52dbf3a58829a6048c83909d39fc5b7f32783489d` |

The jar is **not committed to git** (`build/` is gitignored). Build it with:

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
cd linggango-rtp
sh gradlew clean build --no-daemon --console=plain
sha256sum build/libs/linggango_rtp-1.1.0.jar
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

Two deploys, both while the server was stopped (clean windows).

### Deploy 1 - memfix guard removal + rtp 1.0.0

| Action | Result |
| --- | --- |
| Backup | `backups/deploy-20261011-012520/` (memfix-1.0.0.jar + memfix-common.toml) |
| New mod | uploaded `mods/linggango_rtp-1.0.0.jar` |
| memfix | `mods/memfix-1.0.0.jar` removed, `memfix-1.1.0.jar` uploaded |
| memfix config | `[memfix.rtp]` table removed (4196 a' 2945 bytes); sweep settings untouched |

The removed memfix table held `guard = true` and `pregenerate = true`. memfix
1.1.0 no longer defines those keys, so leaving the table would only produce
"unknown config key" noise; it was removed outright.

### Deploy 2 - rtp 1.1.0 (the stall fix)

After the operator's first `/rtp` on 1.0.0, the server showed `Can't keep up!`
with stalls up to 24.6 s (491 ticks behind) and the search still failed. Root
cause and fix are in `../README.md` (*Why existingChunksOnly*); the operational
change is that 1.1.0 rejects ungenerated candidates before requesting them.

| Action | Result |
| --- | --- |
| Backup | `backups/deploy-20261011-020945/` (linggango_rtp-1.0.0.jar + linggango-rtp-common.toml) |
| Mod | `mods/linggango_rtp-1.0.0.jar` removed, `mods/linggango_rtp-1.1.0.jar` uploaded |
| Config | `config/linggango-rtp-common.toml` **removed** so Forge regenerates the new defaults |

The config had to go: Forge merges an existing config over the new spec, so the
1.0.0 values (`maxRadius = 32000`, `findAttempts = 10`, no `existingChunksOnly`)
would have survived and the fix would not have taken effect. The old file is in
the backup; a fresh one with the 1.1.0 defaults is written on the next start.

## Verify after the next start

Watch `logs/latest.log` for:

- `[rtp] loaded` - the mod loaded.
- **No** `[memfix] /rtp guard installed` line - the old guard is gone.
- **No** `[memfix] pregen:` lines - the pregenerator is gone.
- The memfix startup line should now be only `[memfix] loaded 36 leak targets`.

Then, as an **op in the overworld**, run `/rtp` several times:

- Each run should report a found location and land in terrain, and it should be
  **fast** (well under a second, not ~a minute). If it reports "Could not find a
  valid location", the on-disk gate is rejecting too much - check the config
  (see below) and record the log lines.
- **Watch the CPU and `logs/latest.log` for `Can't keep up!` / `ServerHangWatchdog`.**
  This is the specific regression 1.1.0 fixes: on 1.0.0 the server stalled up to
  24.6 s per `/rtp`. There must be **no** long-tick report during or after a
  search. If there is, the gate is not working - roll back and report.
- Run `/rtp` twice quickly; the second must produce FTB's cooldown message
  (delegation to FTB is intact).
- Run `/back` after an `/rtp`; it must return to the pre-`/rtp` position (FTB's
  teleport history still ran, i.e. the mod delegated rather than moving the
  player itself).

If `/rtp` still fails with "Could not find a valid location" and the config
regenerated correctly, the generated area may be smaller than `maxRadius`. Lower
`maxRadius` (e.g. to 1024) and retry: the gate then only samples terrain that
exists.

## Config

`config/linggango-rtp-common.toml` (regenerated on first load of 1.1.0):

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch; when false, `/rtp` is FTB's unchanged |
| `centerX` / `centerZ` | `0` / `0` | Search area centre |
| `minRadius` | `500` | Minimum distance from centre |
| `maxRadius` | `2048` | Maximum distance from centre |
| `findAttempts` | `40` | Candidates to try before giving up |
| `existingChunksOnly` | `true` | Never generate terrain; only land in chunks already on disk |
| `chunkMargin` | `1` | Neighbour chunks required on disk (1 = full 3x3) |
| `respectClaims` | `true` | Skip candidates inside an FTB Chunks claim |
| `excludedBiomes` | oceans, rivers, deep dark, mushroom fields, void, outer End | Biomes to avoid |

`minRadius`/`maxRadius` are this mod's own bounds. FTB's `rtp.min_distance` /
`rtp.max_distance` (currently `500` / `2048`) are not consulted by the search;
the defaults were chosen to match them.

**Note on Forge config merging:** editing the spec does not change an existing
config file - Forge keeps the values already on disk. When a new default must
take effect, delete the config file (as deploy 2 did) so it regenerates.

## Rollback

1. Remove the mod:
   ```bash
   rm mods/linggango_rtp-1.1.0.jar
   ```
2. Optionally restore rtp 1.0.0 (not recommended - it stalls the server):
   ```bash
   cp backups/deploy-20261011-020945/linggango_rtp-1.0.0.jar mods/
   cp backups/deploy-20261011-020945/linggango-rtp-common.toml config/
   ```
3. Restore memfix 1.0.0 (optional - 1.1.0 is a drop-in with the guard removed):
   ```bash
   rm mods/memfix-1.1.0.jar
   cp backups/deploy-20261011-012520/memfix-1.0.0.jar mods/memfix-1.0.0.jar
   cp backups/deploy-20261011-012520/memfix-common.toml config/memfix-common.toml
   ```
4. Restart. With `linggango_rtp` gone, `/rtp` returns to FTB Essentials' built-in
   behaviour (which generates terrain and stalls).

Do not delete the `backups/deploy-20261011-*` directories until the deploy is
confirmed stable.

## Honest status

This mod was built, unit-tested (27 tests) and statically inspected, but has
**never been executed against a live client** - the build environment has no
Minecraft client, so the interception, the on-disk gate, the async chunk load,
the landing validation and the FTB delegation have not run in-game. 1.0.0's
first live run stalled the server (24.6 s); 1.1.0 is the fix for that, and it is
unproven. The verify section above is the acceptance test. If any of it fails,
roll back.
