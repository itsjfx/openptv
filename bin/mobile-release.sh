#!/usr/bin/env bash

# Signed release pipeline for the mobile app. Two subcommands so the workflow
# can gate cheaply before paying for a build:
#
#   gate     decide whether this push/dispatch should cut a release, write
#            release=<bool> / version=<name> / code=<int> to $GITHUB_OUTPUT
#   publish  assembleRelease (signed via KEYSTORE_* env), verify the signature,
#            then create/replace the GitHub Release tagged mobile-v<version>
#
# Signing creds come from the environment (KEYSTORE_PATH / KEYSTORE_PASSWORD /
# KEY_ALIAS / KEY_PASSWORD); the build leaves the APK unsigned if they're
# absent, so `publish` checks first and fails loudly rather than shipping an
# unsigned APK to the Releases page.

set -eu -o pipefail

cd "$(dirname "$(readlink -f "$0")")/.."

gradle_file="mobile/app/build.gradle.kts"
apk_src="mobile/app/build/outputs/apk/release/app-release.apk"

version_name() { grep -oP 'versionName\s*=\s*"\K[^"]+' "$gradle_file"; }
version_code() { grep -oP 'versionCode\s*=\s*\K[0-9]+' "$gradle_file"; }

emit() { # key=value -> $GITHUB_OUTPUT (or stdout when run locally)
  echo "$1" >>"${GITHUB_OUTPUT:-/dev/stdout}"
}

gate() {
  local name code release=false
  name="$(version_name)"
  code="$(version_code)"

  if [[ "${FORCE:-false}" == "true" ]]; then
    echo "Forced release of $name (code $code)." >&2
    release=true
  elif git diff --unified=0 'HEAD^' HEAD -- "$gradle_file" 2>/dev/null \
       | grep -qE '^\+\s*(versionName|versionCode)\s*='; then
    echo "Detected a version bump to $name (code $code)." >&2
    release=true
  else
    echo "No version change in $gradle_file — skipping release." >&2
  fi

  emit "release=$release"
  emit "version=$name"
  emit "code=$code"
}

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

publish() {
  local name code tag short subject built apk notes
  name="$(version_name)"
  code="$(version_code)"
  tag="mobile-v${name}"

  if [[ -z "${KEYSTORE_PATH:-}" || ! -f "${KEYSTORE_PATH:-}" ]]; then
    echo "::error::KEYSTORE_PATH is unset or missing — refusing to publish an unsigned release. See mobile/RELEASE.md." >&2
    exit 1
  fi

  echo "Building signed release $tag (versionCode $code)..." >&2
  ( cd mobile && ./gradlew :app:assembleRelease )

  verify_signature "$apk_src"

  apk="openptv-${name}.apk"
  cp -- "$apk_src" "$apk"

  short="${GITHUB_SHA:0:7}"
  subject="$(git log -1 --format='%s' "${GITHUB_SHA:-HEAD}")"
  built="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"

  notes="$(mktemp)"
  trap 'code="$?"; rm -f -- "$notes"; exit "$code"' EXIT
  cat >"$notes" <<EOF
# OpenPTV ${name}

Signed release build — versionCode \`${code}\`, versionName \`${name}\`.

Built from commit \`${short}\` (${subject}) on ${built}.

## Install

Download \`${apk}\` below and sideload it. Verify the signing certificate before installing:

\`\`\`
apksigner verify --print-certs ${apk}
\`\`\`

The SHA-256 must match the fingerprint published in [\`mobile/RELEASE.md\`](${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-itsjfx/openptv}/blob/master/mobile/RELEASE.md).
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
}

case "${1:-}" in
  gate) gate ;;
  publish) publish ;;
  *) echo "usage: $0 {gate|publish}" >&2; exit 2 ;;
esac
