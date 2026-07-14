#!/usr/bin/env bash

# Sourced by bin/mobile-release.sh and bin/mobile-preview-release.sh. The
# release notes' "What's new" section is human-written and lives in
# mobile/CHANGELOG.md; this pulls one section out of it.

changelog="mobile/CHANGELOG.md"

# Body of the `## <heading>` section, blank lines trimmed off both ends. Empty
# output means the section is missing or has nothing in it — callers decide
# whether that's fatal.
changelog_section() {
  local heading="$1"
  awk -v want="## $heading" '
    $0 == want { found = 1; next }
    found && /^## / { exit }
    found { print }
  ' "$changelog" | sed -e '/./,$!d' -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}
