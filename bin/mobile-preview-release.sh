#!/usr/bin/env bash

set -eu -o pipefail

preview_tag="preview"
apk_name="openptv-preview.apk"
apk_src="mobile/app/build/outputs/apk/debug/app-debug.apk"

cd "$(dirname "$0")/.."

source bin/lib/changelog.sh

cp -- "$apk_src" "$apk_name"

short="${GITHUB_SHA:0:7}"
subject="$(git log -1 --format='%s' "$GITHUB_SHA")"
built="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
commit_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"
download_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/releases/download/${preview_tag}/${apk_name}"

# Whatever a human has queued up under `## Unreleased` — i.e. what this preview
# has that the last signed release doesn't. Omitted entirely when empty.
whats_new="$(changelog_section Unreleased)"

notes="$(mktemp)"
trap 'code="$?"; rm -f -- "$notes"; exit "$code"' EXIT

{
  cat <<EOF
# openptv preview build
EOF

  if [[ -n "$whats_new" ]]; then
    cat <<EOF

## What's new since the last release

${whats_new}
EOF
  fi

  cat <<EOF

## Build info

Automated build from commit: [\`${short}\`](${commit_url}) — ${subject}

Built on: ${built}

## Download

- [${apk_name}](${download_url})

This is an unsigned debug APK rebuilt on every push to \`master\` that touches \`mobile/\`. It is **not** a stable release and may contain unfinished features.
EOF
} >"$notes"

if gh release view "$preview_tag" >/dev/null 2>&1; then
  gh release delete "$preview_tag" --yes --cleanup-tag
fi

gh release create "$preview_tag" \
  --target "$GITHUB_SHA" \
  --title "openptv preview ($(date -u '+%Y-%m-%d'))" \
  --notes-file "$notes" \
  --prerelease \
  "$apk_name"
