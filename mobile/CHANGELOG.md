# Changelog

Hand-written. The section for a version becomes the **What's new** block on that
version's GitHub Release page — `bin/mobile-release.sh` splices it in — so write
it for someone about to sideload the APK, not for someone reading the diff. No
part of this file is generated.

How to keep it up to date:

- Add a line to `## Unreleased` in the same PR as the change. It shows up in the
  preview release notes straight away.
- When you bump `versionName` in [`app/build.gradle.kts`](app/build.gradle.kts)
  to cut a release, rename `## Unreleased` to `## <versionName>` and open a fresh
  empty `## Unreleased` above it.

The release fails if the version being released has no section here, or the
section is empty. The notes are an input to the release, not an afterthought.

## Unreleased

## 0.2.0

- Released before this changelog existed. See the
  [commit log](https://github.com/itsjfx/openptv/compare/mobile-v0.1.0...mobile-v0.2.0).

## 0.1.0

- First release.
