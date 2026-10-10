# Deploying Linggango RTP

Operator runbook for installing `linggango_rtp` on the live Linggango server and
retiring the `linggango-memfix` `/rtp` guard + pregenerator.

> **STOP — go/no-go required.** This mod has **NOT been executed against a live
> client or a real server**. It was built, unit-tested (18 tests) and statically
> inspected only; no Minecraft client exists in the build environment, so the
> `/rtp` interception, the async chunk load, the landing validation and the FTB
> delegation have never actually run against Minecraft. Because deploying it
> *and disabling the memfix guard* removes the only thing currently preventing
> `/rtp` from stalling the server thread, a human operator must explicitly
> approve this deploy (a **go**) before running any step below. Until then the
> server keeps its current behaviour: memfix guard **on**, pregenerate **on**.

## Artifact

| | |
| --- | --- |
| File | `build/libs/linggango_rtp-1.0.0.jar` |
| Size | 21,475 bytes |
| SHA-256 | `9f243ce1ae1298f2633233eedd21fd5a6914d1f0bacc4fe79c94401bd566e317` |

The jar is **not committed to git** (`build/` is gitignored). Build it with:

```bash
export JAVA_HOME=/workspace/tools/jdk-17.0.13+11
export PATH=$JAVA_HOME/bin:$PATH
cd linggango-rtp
sh gradlew clean build --no-daemon --console=plain
sha256sum build/libs/linggango_rtp-1.0.0.jar   # must match the value above
```

If the hash differs from the one in this document, stop and re-verify the build
before deploying — the steps below assume the artifact above.

## Target

| | |
| --- | --- |
| Protocol | SFTP |
| Host | `fr-zero.acorehosting.com` |
| Port | `2022` |
| User | `amrmostafa800.678cb0d5` |
| Access | chrooted: the server root is the login directory; `mods/` and `config/` are relative to it |

**Credentials are not stored in this file.** Supply the SFTP password through the
environment for the session, e.g. `SFTP_PASSWORD` consumed by your `sshpass` /
`lftp` / `sftp` client, or use an SSH key. Never commit it.

## Prerequisites

- Go/no-go approved by a human operator (see the STOP note above).
- The release jar built locally with a matching SHA-256.
- SFTP credentials available in the environment (not in this repo).
- A maintenance window: the server will be restarted twice (deploy + rollback if
  needed), and the memfix guard is off for the duration.

## Deploy steps

Set these once for the session (password comes from the environment):

```bash
SFTP_HOST=fr-zero.acorehosting.com
SFTP_PORT=2022
SFTP_USER=amrmostafa800.678cb0d5
JAR=build/libs/linggango_rtp-1.0.0.jar
```

### (a) Back up `config/memfix-common.toml`

Copy the remote memfix config into a timestamped directory on the server *before*
changing anything, so rollback is a copy-back:

```bash
STAMP=$(date +%Y%m%d-%H%M%S)
# via sftp/lftp/scp on the chrooted session:
mkdir  backups/memfix-$STAMP
cp     config/memfix-common.toml  backups/memfix-$STAMP/memfix-common.toml
```

Verify the backup exists and is non-empty before continuing.

### (b) Upload the jar

Upload the built jar into the server's `mods/` directory:

```bash
put $JAR mods/linggango_rtp-1.0.0.jar
```

### (c) Edit `config/memfix-common.toml`

**Read the current remote file first.** Do not assume the keys exist — an
operator may have already edited them. Only change the guard/pregenerate keys and
leave every other line byte-identical.

```bash
get config/memfix-common.toml /tmp/memfix-common.toml
```

Then, in the `[memfix.rtp]` table:

- set `guard = false` (stops memfix from declining `/rtp` candidates whose chunk
  is not yet generated — the guard would otherwise fight this mod);
- set `pregenerate = false` (the pregenerator is no longer needed, and it must not
  race the new search).

Leave `dimension`, `radiusBlocks`, `centerX`, `centerZ`, `chunksPerTick` and
`maxInFlight` untouched (they are inert while `pregenerate = false`).

**If the file has no `[memfix.rtp]` table, or has no `guard` / `pregenerate`
keys:** do **not** invent them and do **not** guess. Record the exact finding
(the file contents, or the absence of the keys) in the deploy notes and stop for
another go/no-go decision — a missing guard key means this runbook's premise
about the running config is wrong and the deploy must be re-assessed.

Upload the edited file back:

```bash
put /tmp/memfix-common.toml config/memfix-common.toml
```

### (d) Restart the server

Restart through the host's normal mechanism (panel or console), not by killing
the process. On boot, confirm in the log:

```
[rtp] loaded
```

and that memfix no longer reports the guard as installed/active, e.g. it should
**not** print a line installing the `/rtp` guard. If the guard still reports
installed, the `guard = false` edit did not take effect — stop and re-check (c).

### (e) Smoke test

As an **op** in the **overworld**, run `/rtp` several times (say five). For each:

- The player should be told it found a location and land in terrain **outside
  the previously-generated ring** (the area the memfix pregenerator had covered,
  i.e. beyond `radiusBlocks`/`rtp.max_distance`). Confirm the actual landing with
  `/tp` to read the position, or with the coordinates in the log — do not rely on
  the message alone.
- Watch the log for `ServerHangWatchdog`: there must be **no** long-tick report
  during or after the searches. A `ServerHangWatchdog` entry means the search is
  stalling the server thread and the deploy must be rolled back.

### (f) Confirm delegation to FTB Essentials

- Run `/rtp` twice in quick succession. The second run must still produce FTB's
  **cooldown** message (the mod does not start a search while FTB reports a
  cooldown, and it delegates the teleport to FTB, so FTB's cooldown is intact).
- Run `/back` after an `/rtp`. It must return the player to the pre-`/rtp`
  position, proving FTB's teleport history (`addTeleportHistory`) still ran — i.e.
  the mod delegated the teleport rather than moving the player itself.

If any of (d)–(f) fails, roll back immediately (below).

## Rollback

Rollback restores the exact pre-deploy state.

1. Remove the mod jar from `mods/`:
   ```bash
   rm mods/linggango_rtp-1.0.0.jar
   ```
2. Restore the backed-up memfix config:
   ```bash
   cp backups/memfix-$STAMP/memfix-common.toml config/memfix-common.toml
   ```
   (Use the `$STAMP` from step (a). This restores `guard` and `pregenerate` to
   their original values.)
3. Restart the server. Confirm the log shows the memfix `/rtp` guard installed
   again and no `[rtp] loaded` line.
4. Verify `/rtp` behaves as it did before the deploy.

Do **not** delete the `backups/memfix-$STAMP` directory until the deploy is
confirmed stable.
