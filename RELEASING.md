# Releasing the Android build

The APK can be signed on a workstation, but the supported path is the GitHub workflow: a `v*` tag
builds, signs and publishes the APK and the AAB. The keystore reaches CI only as a repository
secret, never as a committed file. This file records the steps and the two facts that already
broke an upgrade path once.

## 1. Signing identity

| Item | Value |
| --- | --- |
| Keystore | `keystore/release.keystore` (gitignored; CI receives it as `RELEASE_KEYSTORE_BASE64`) |
| Alias | `zeidex` |
| Store password | `KEYSTORE_PASSWORD` / `KEY_PASSWORD` env vars (fallback in `app/build.gradle.kts`) |
| Certificate SHA-256 | `d24bf9dfb3f1b9acb0f48fb1940b3eacc3875cdb19e7869f4b1281fdecb9366e` |

`UpdateChecker.OFFICIAL_CERT_SHA256` and both update manifests pin that fingerprint.

### The v1.2.0 asset in this repository is signed by another key

`zeidex-convert-v1.2.0.apk` on this repository's release page has certificate
`fb4994b5d949d26a94f9c7e9e2010486eaa0c0657d402155aba51e896959ca4f` (same subject DN, different
key, no v3 rotation lineage). The copy on the parent repository's `v0.1.0` release is the
`d24bf9df…` build. Android installs an update only when the signer matches the installed app, so:

- a device carrying the `fb4994b5…` build **cannot** install a `d24bf9df…` APK until the app is
  uninstalled;
- never advertise a `d24bf9df…` APK to those devices as an in-place update. `UpdateChecker`
  refuses it before download now, but the user still has to reinstall.

Recover the `fb4994b5…` keystore if it still exists and either keep signing with it or rotate
with `apksigner rotate --in … --out …` so the lineage is preserved. Do not rotate silently.

## 2. Build

```bash
cd android
gradle :app:assembleRelease :app:bundleRelease --no-daemon --console=plain
```

Outputs:

- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/bundle/release/app-release.aab`

## 3. Verify before publishing

```bash
APK=app/build/outputs/apk/release/app-release.apk
BT=$ANDROID_HOME/build-tools/35.0.0
"$BT/apksigner" verify --print-certs "$APK"
"$BT/aapt2" dump badging "$APK" | head -1
sha256sum "$APK"
```

The certificate must be `d24bf9df…`, the package `com.zeidex.convert`, and the version must equal
`versionName`/`versionCode` in `app/build.gradle.kts`.

## 4. Update the manifests in the same commit

Two files describe the same release:

- `updates/manifest.json` — this repository's copy (what the app's fallback URL used to serve).
- `../packaging/updates/manifest.json` — the unified registry read by `zeidex-convert update`.

Set `version`, `version_code`, `release_date`, `url`, `mirror_url`, the **actual** `sha256` of the
file you are about to upload, and both changelogs. `UpdateCheckerTest` fails the build when the
manifest version or version code drifts from `BuildConfig`, and the Rust test rejects malformed or
placeholder digests.

The client refuses a download whose SHA-256 does not match, so publishing a manifest before the
APK is uploaded is safe: users get a checksum error, not a bad install.

`RELEASE_NOTES.md` in the repository root is the release description. The workflow copies it into
the release, and edits the notes when the release already exists, so a tag pushed with a stale
file leaves the previous text in place — write it before tagging.

The Android package in this repository's manifest points `url` at the GitHub release asset, which
is the only host that currently serves the APK with the right content type, and keeps
`mirror_url` on `zeidexsoft.com` for when the site is fixed (section 5).

## 5. Hosting (currently broken outside the repository)

The app fetches `https://zeidexsoft.com/updates/manifest.json`. On 2026-11-13 that URL answered
`200 text/html` with the site's SPA shell (84,529 bytes), and
`https://zeidexsoft.com/downloads/zeidex-convert-v1.2.0.apk` answered with the same HTML. The
Android client now rejects a non-JSON body and moves to the fallback, but the fallback lives in a
private repository, so an anonymous device cannot read either source.

Cloudflare Pages serves a real asset before applying `_redirects`, so the fix is to upload two
static files to the deployed site:

```
/updates/manifest.json
/downloads/zeidex-convert-v<version>.apk
```

Do that from the site repository or with `wrangler pages deploy`, then confirm:

```bash
curl -s https://zeidexsoft.com/updates/manifest.json | head -c 1   # must print {
curl -sI https://zeidexsoft.com/downloads/zeidex-convert-v1.2.0.apk | grep -i content-type  # application/vnd.android.package-archive
```

Until those two responses are correct, in-app update checks report that no readable manifest was
found. That is the honest state; no code change can host the files.

## 6. CI release workflow

`.github/workflows/release.yml` runs on a `v*` tag. It refuses to build unless these repository
secrets exist:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 keystore/release.keystore` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `zeidex` (optional, defaults to `zeidex`) |

The job used to generate a fresh keystore when the secret was missing. That is how the `v1.2.0`
asset acquired a key no other build uses: every tag would have produced another unusable signer.
The fallback is gone — a missing key now fails the job with an explicit error.

After signing, the workflow prints the APK's certificate and aborts unless it is
`d24bf9dfb3f1b9acb0f48fb1940b3eacc3875cdb19e7869f4b1281fdecb9366e`. A wrong key can therefore no
longer reach the release page, even if a secret is misconfigured.

`.github/workflows/android.yml` runs `testDebugUnitTest`, so the manifest/version guard fails the
build before an APK is assembled.
