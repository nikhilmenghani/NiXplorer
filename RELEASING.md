# Releasing NiXplorer

NiXplorer has two independently installable channels. Stable uses the existing `com.nixplorer` package and the `NiXplorer` label. Dev uses `com.nixplorer.debug` and the `NiXplorer Dev` label. Their FileProvider authorities are derived from the resolved application ID.

## Versions

`release.txt` contains a stable semantic version such as `1.3.2`. `dev.txt` contains a prerelease version such as `1.3.3-dev.1`. Each file contains exactly:

```text
versionName=1.3.3-dev.1
versionCode=11
```

Every new publication, regardless of channel, must use a `versionCode` greater than both files' previous values. Before publishing, update the selected channel so its code is greater than the other channel's current code. Codes are never reused. Tags are `v<versionName>` and the workflow rejects malformed files, a selected code that is not globally newest, and existing tags/releases.

## Publishing

Pushes and pull requests run lint, unit tests, and a debug build without publishing. To publish, open **Actions → Android CI and release → Run workflow** and choose `dev` or `stable`.

- `dev` builds the debug application ID with persistent release signing and creates a GitHub prerelease.
- `stable` builds the release variant with the existing stable application ID and creates a normal GitHub release.

The workflow verifies the APK signature and attaches both `NiXplorer-<version>.apk` and its `.sha256` checksum. Debug update checks consider prereleases only; stable update checks consider normal releases only.

## Required GitHub Actions secrets

- `RELEASE_KEYSTORE_BASE64`: the persistent JKS/PKCS12 keystore, base64 encoded as a single value.
- `RELEASE_STORE_PASSWORD`: keystore password.
- `RELEASE_KEY_ALIAS`: signing key alias.
- `RELEASE_KEY_PASSWORD`: signing key password.

Keep the keystore and credentials backed up securely. Losing them prevents future stable APKs from being signed with the same identity. Never replace this keystore for an existing stable package.
