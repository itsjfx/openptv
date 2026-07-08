# Releasing the mobile app

The signed release pipeline (issue #50). A release is a **signed** `app-release.apk`
published to GitHub Releases under the tag `mobile-v<versionName>`.

This is separate from the **preview** channel (`mobile-prerelease.yml`), which ships
an *unsigned debug* APK on every push to `master`. Only the pipeline described here
touches the signing keystore.

## Cutting a release

1. Bump `versionCode` (and usually `versionName`) in
   [`app/build.gradle.kts`](app/build.gradle.kts). `versionCode` **must** increase
   every release — Android refuses to install an APK with a lower code over an
   existing one.
2. Merge to `master`. The `mobile-release` workflow detects the changed version
   line and builds + publishes the signed APK automatically.
3. Alternatively, run **Actions → mobile-release → Run workflow** to build a release
   from the current `master` without a version bump (the `force` input defaults to
   on). Re-running an existing tag replaces that release in place.

The build leaves the APK **unsigned** when signing credentials are absent, so a
plain `assembleRelease` on a fresh checkout still succeeds; the release script
refuses to publish an unsigned APK.

## Signing config

`:app` reads four values, from environment variables first (CI) then
`mobile/local.properties` (a local release build). Both sources are git-ignored, so
no key material is ever committed:

| Key                 | Meaning                          |
| ------------------- | -------------------------------- |
| `KEYSTORE_PATH`     | Path to the `.jks` keystore      |
| `KEYSTORE_PASSWORD` | Keystore (store) password        |
| `KEY_ALIAS`         | Key alias (`openptv`)            |
| `KEY_PASSWORD`      | Key password (same as the store) |

To build a signed release locally, put the four values in
`mobile/local.properties` (git-ignored) and run
`cd mobile && ./gradlew :app:assembleRelease`.

## The keystore

- **Keystore:** PKCS12, RSA 4096, valid ~27 years. Held privately by the
  maintainer — never in the repo.
- **Signing certificate SHA-256** — verify every published APK against this:

  ```
  CE:3E:2D:12:4B:8C:5E:A9:39:45:D5:FA:36:5B:CD:E3:CC:39:02:BB:B4:22:50:A3:60:04:73:F8:05:6A:48:CE
  ```

  ```sh
  apksigner verify --print-certs openptv-<version>.apk
  ```

### Rotation policy

This certificate is the app's permanent identity for sideloading and F-Droid: every
future update **must** be signed with the same key, or users have to uninstall and
reinstall (losing their data). Treat the keystore as un-rotatable:

- **Back it up off-machine** (encrypted). Losing it means no in-place upgrades, ever.
- Never commit it, never paste the passwords anywhere shared.
- If it is ever compromised there is no clean rotation — it would require a new
  application id and a migration. Guard it accordingly.

## CI secrets (one-time, repo admin only)

The `mobile-release` workflow needs these four repository secrets. The keystore is
base64-encoded into `KEYSTORE` and decoded back to a file on the runner. Setting
secrets requires **admin** on the repo, so the maintainer runs this from a shell
authenticated as an admin account:

```sh
base64 -w0 path/to/release.jks | gh secret set KEYSTORE --repo itsjfx/openptv
gh secret set KEYSTORE_PASSWORD --repo itsjfx/openptv # prompts for the value
gh secret set KEY_ALIAS         --repo itsjfx/openptv --body openptv
gh secret set KEY_PASSWORD      --repo itsjfx/openptv # prompts for the value
```

Verify with `gh secret list --repo itsjfx/openptv` — it should list all four. No
secret value is ever printed by these commands.
