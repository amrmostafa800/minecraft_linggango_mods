# Release process

The compiled jar is **not** committed to git. It is attached to a GitHub Release as a
downloadable asset. A jar is a binary that changes on every build; keeping it out of the
tree means history stays diffable and there is never a stale copy in `src`.

## Artifact

| | |
| --- | --- |
| File | `linggangosync-1.0.0+mc1.20.1.jar` |
| Size | 106,059 bytes |
| SHA-256 | `591e00eadfe4b6ee565d2620a49613d8c55893feb4f76a933f460002c1b572ef` |

## Cutting a release

```bash
export JAVA_HOME=/path/to/jdk-17

# 1. Clean rebuild; the test suite runs as part of build.
gradle clean build

# 2. Confirm tests passed and inspect the artifact.
ls -la build/libs/
sha256sum build/libs/*.jar

# 3. Sanity-check the jar is a valid, complete mod.
python3 -c "
import zipfile
z = zipfile.ZipFile('build/libs/linggangosync-1.0.0+mc1.20.1.jar')
assert z.testzip() is None, 'corrupt jar'
for required in ('META-INF/mods.toml', 'linggangosync.mixins.json',
                 'linggangosync.refmap.json', 'pack.mcmeta'):
    assert required in z.namelist(), f'missing {required}'
print('jar OK:', len(z.namelist()), 'entries')
"

# 4. Stage the asset and its checksum.
mkdir -p dist && cp build/libs/*.jar dist/
cd dist && sha256sum *.jar > *.jar.sha256 && cd ..

# 5. Tag and push.
git tag -a vX.Y.Z -m "Linggango Sync vX.Y.Z"
git push origin vX.Y.Z

# 6. Create the release and upload assets (see below).
```

## Creating the release

Release assets on a **private** repository require authentication to download. The plain
`https://github.com/.../releases/download/...` URL returns `Not Found` for anonymous
requests; authenticated clients should use the asset API endpoint:

```bash
curl -sL -H "Authorization: Bearer $GITHUB_TOKEN" \
     -H "Accept: application/octet-stream" \
     -o linggangosync.jar \
     https://api.github.com/repos/amrmostafa800/minecraft_linggango_sync/releases/assets/<ASSET_ID>
```

Get `<ASSET_ID>` from:

```bash
curl -s -H "Authorization: Bearer $GITHUB_TOKEN" \
  https://api.github.com/repos/amrmostafa800/minecraft_linggango_sync/releases \
  | python3 -m json.tool
```

Anyone with repository access can also download the asset from the release page in a
browser while logged in.

## Verifying a downloaded jar

Always verify the checksum before dropping a jar into `mods/`:

```bash
sha256sum -c linggangosync-1.0.0+mc1.20.1.jar.sha256
```

On Windows PowerShell:

```powershell
(Get-FileHash .\linggangosync-1.0.0+mc1.20.1.jar -Algorithm SHA256).Hash.ToLower()
# compare against the .sha256 file
```

## Checklist before publishing

- [ ] `gradle clean build` succeeds
- [ ] All 78 tests pass
- [ ] `sha256sum` recorded in the release notes
- [ ] Jar contains `mods.toml`, `mixins.json`, `refmap.json`, `pack.mcmeta`
- [ ] `refmap.json` resolves to SRG names (`f_` / `m_`), not official names
- [ ] Version in `gradle.properties` matches the tag
- [ ] No `SERVER_AUTHORITATIVE` default was relaxed
- [ ] Any new optimisation has a measured before/after figure
