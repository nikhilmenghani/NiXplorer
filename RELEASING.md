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

Pushes and pull requests start this workflow only when `dev.txt` or `release.txt` changes. They run version validation, unit tests, and a debug build. A push to the repository's default branch automatically publishes when exactly one version file changes:

- Change only `dev.txt` to publish a dev prerelease.
- Change only `release.txt` to publish a stable release.

Changing both version files in one push is rejected so the release channel is never ambiguous. Version-file changes on non-default branches and pull requests only verify; publication happens after the change reaches the default branch. Changes to code, documentation, or the workflow itself do not start this workflow unless the same commit range also changes a version file. Manual dispatch remains available under **Actions → Android CI and release → Run workflow** as a recovery option.

- `dev` builds the debug application ID with persistent release signing and creates a GitHub prerelease.
- `stable` builds the release variant with the existing stable application ID and creates a normal GitHub release.

The workflow verifies the APK signature and attaches both `NiXplorer-<version>.apk` and its `.sha256` checksum. Debug update checks consider prereleases only; stable update checks consider normal releases only.

## Required GitHub Actions secrets

- `RELEASE_KEYSTORE_BASE64`: the persistent JKS/PKCS12 keystore, base64 encoded as a single value.
- `RELEASE_STORE_PASSWORD`: keystore password.
- `RELEASE_KEY_ALIAS`: signing key alias.
- `RELEASE_KEY_PASSWORD`: signing key password.

Keep the keystore and credentials backed up securely. Losing them prevents future stable APKs from being signed with the same identity. Never replace this keystore for an existing stable package.
