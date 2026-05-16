#!/usr/bin/env bash

set -eu -o pipefail

preview_tag="preview"
apk_name="openptv-preview.apk"
apk_src="mobile/app/build/outputs/apk/debug/app-debug.apk"

cd "$(dirname "$0")/.."

cp -- "$apk_src" "$apk_name"

short="${GITHUB_SHA:0:7}"
subject="$(git log -1 --format='%s' "$GITHUB_SHA")"
built="$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
commit_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}"
download_url="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/releases/download/${preview_tag}/${apk_name}"

notes="$(mktemp)"
trap 'rm -f -- "$notes"' EXIT

cat >"$notes" <<EOF
# openptv preview build

Automated build from commit: [\`${short}\`](${commit_url}) — ${subject}

Built on: ${built}

## Download

- [${apk_name}](${download_url})

This is an unsigned debug APK rebuilt on every push to \`master\` that touches \`mobile/\`. It is **not** a stable release and may contain unfinished features.
EOF

if gh release view "$preview_tag" >/dev/null 2>&1; then
  gh release delete "$preview_tag" --yes --cleanup-tag
fi

gh release create "$preview_tag" \
  --target "$GITHUB_SHA" \
  --title "openptv preview ($(date -u '+%Y-%m-%d'))" \
  --notes-file "$notes" \
  --prerelease \
  "$apk_name"
