#!/usr/bin/env bash

# Signed release pipeline for the mobile app. Decides whether this
# push/dispatch should cut a release (versionName/versionCode changed in the
# last commit), then assembleRelease (signed via KEYSTORE_* env), verifies the
# signature, and creates/replaces the GitHub Release tagged mobile-v<version>.
# A run with no version change exits 0 as a no-op. `--force` (or a non-empty
# FORCE env var) skips the version-change check and releases regardless.
#
# Signing creds come from the environment (KEYSTORE_PATH / KEYSTORE_PASSWORD /
# KEY_ALIAS / KEY_PASSWORD); the build leaves the APK unsigned if they're
# absent, so we check first and fail loudly rather than shipping an unsigned
# APK to the Releases page.
#
# The "What's new" half of the notes is written by a human in
# mobile/CHANGELOG.md — a version with no entry there doesn't get released.

set -eu -o pipefail

cd "$(dirname "$(readlink -f "$0")")/.."

source bin/lib/changelog.sh

gradle_file="mobile/app/build.gradle.kts"
apk_src="mobile/app/build/outputs/apk/release/app-release.apk"

version_name() { grep -oP 'versionName\s*=\s*"\K[^"]+' "$gradle_file"; }
version_code() { grep -oP 'versionCode\s*=\s*\K[0-9]+' "$gradle_file"; }

verify_signature() {
  local apk="$1" apksigner
  apksigner="$(ls "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
  if [[ -z "$apksigner" ]]; then
    echo "apksigner not found — skipping signature verification." >&2
    return 0
  fi
  echo "Verifying APK signature:" >&2
  "$apksigner" verify --print-certs "$apk" >&2
}

# Signing cert SHA-256 fingerprint of the APK, colon-separated uppercase to
# match mobile/RELEASE.md. keytool ships with the JDK, so it's always present
# where the build just ran.
cert_sha256() {
  keytool -printcert -jarfile "$1" | grep -oPm1 'SHA256:\s*\K[0-9A-F:]+'
}

force=0
[[ -n "${FORCE:-}" ]] && force=1

while (( $# )); do
  case "$1" in
    --force) force=1 ;;
    *) echo "usage: $0 [--force]" >&2; exit 2 ;;
  esac
  shift || true
done

name="$(version_name)"
code="$(version_code)"
tag="mobile-v${name}"

if (( force )); then
  echo "Forced release of $name (code $code)." >&2
elif git diff --unified=0 'HEAD^' HEAD -- "$gradle_file" 2>/dev/null \
     | grep -qE '^\+\s*(versionName|versionCode)\s*='; then
  echo "Detected a version bump to $name (code $code)." >&2
else
  echo "No version change in $gradle_file — skipping release." >&2
  exit 0
fi

if [[ -z "${KEYSTORE_PATH:-}" || ! -f "${KEYSTORE_PATH:-}" ]]; then
  echo "::error::KEYSTORE_PATH is unset or missing — refusing to publish an unsigned release. See mobile/RELEASE.md." >&2
  exit 1
fi

# Fail before the build, not after: a release with no human-written notes is
# incomplete, and the fix (write them) is a code change anyway.
whats_new="$(changelog_section "$name")"
if [[ -z "$whats_new" ]]; then
  echo "::error::${changelog} has no '## ${name}' section (or it's empty) — write the What's new notes for this version before releasing. See mobile/RELEASE.md." >&2
  exit 1
fi

echo "Building signed release $tag (versionCode $code)..." >&2
( cd mobile && ./gradlew :app:assembleRelease )

verify_signature "$apk_src"

apk="openptv-${name}.apk"
cp -- "$apk_src" "$apk"

fingerprint="$(cert_sha256 "$apk")"

short="${GITHUB_SHA:-$(git rev-parse HEAD)}"
short="${short:0:7}"
subject="$(git log -1 --format='%s' "${GITHUB_SHA:-HEAD}")"
built="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"

notes="$(mktemp)"
trap 'code="$?"; rm -f -- "$notes"; exit "$code"' EXIT
cat >"$notes" <<EOF
# OpenPTV ${name}

## What's new

${whats_new}

## Build info

Signed release build — versionCode \`${code}\`, versionName \`${name}\`.

Built from commit \`${short}\` (${subject}) on ${built}.

## Install

Download \`${apk}\` below and sideload it. Verify the signing certificate before installing:

\`\`\`
apksigner verify --print-certs ${apk}
\`\`\`

The SHA-256 must match the fingerprint published in [\`mobile/RELEASE.md\`](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-itsjfx/openptv}/blob/master/mobile/RELEASE.md).

## Signing certificate

SHA-256 fingerprint of the certificate this APK is signed with — the digest from \`apksigner verify --print-certs\` on your download must match:

\`\`\`
${fingerprint}
\`\`\`
EOF

# Replace any existing release on this tag so re-runs / forced dispatches are
# idempotent (mirrors the preview channel's delete-then-create).
if gh release view "$tag" >/dev/null 2>&1; then
  echo "Release $tag already exists — replacing it." >&2
  gh release delete "$tag" --yes --cleanup-tag
fi

gh release create "$tag" \
  --target "${GITHUB_SHA:-HEAD}" \
  --title "OpenPTV ${name}" \
  --notes-file "$notes" \
  "$apk"
