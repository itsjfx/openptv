---
name: pr-attach
description: |
  Attach screenshots and/or an APK to a GitHub PR by uploading them to a secret
  gist and posting a PR comment with embedded image markdown and download links.
  Use when the user asks to post screenshots/APK to a PR, share build artifacts
  on a PR, attach a debug APK to a PR for testers, or attach binaries that can't
  be committed to the repo. Works around `gh gist create` rejecting binary files
  by cloning the gist over git and pushing the binaries.
allowed-tools:
  - Bash
---

# pr-attach: Attach binaries to a GitHub PR via a secret gist

Use the bundled script to upload binary files (PNG screenshots, APKs, etc.) to
a per-PR secret gist and post a single PR comment that embeds the screenshots
and links to the APK download.

## When to use

- "Post these screenshots to PR #N"
- "Attach the debug APK to PR #N so testers can grab it"
- "Share build artifacts on the PR"
- Any time files need to be linked from a PR comment but cannot be committed
  into the repo (e.g. `*.png`, `*.apk`)

## Script

`attach.sh` lives in this skill directory.

```
attach.sh <pr-number> [--apk <path>] [--screenshot <path>]... [--caption <text>]... [--repo <owner/repo>] [--new]
```

- `<pr-number>` is required.
- `--screenshot` and `--caption` are repeatable; captions match screenshots
  positionally. If a caption is omitted, it is derived from the filename
  (basename without extension). Captions are **not persisted** across
  updates — re-running drops them back to filename stems.
- `--apk` is optional. When provided the comment includes a download link and
  the file size.
- `--repo` defaults to the current repo (`gh repo view --json nameWithOwner`).
- `--new` forces a fresh gist + new comment even if a previous pr-attach gist
  already exists for this PR.

The gist is created as **secret** and is named `<repo>#<pr-number>` (e.g.
`itsjfx/openptv#8`) so the script can find it again on subsequent runs.

## Behaviour summary

The script is **idempotent per PR by default**: rerunning on the same PR
reuses the existing gist and edits the existing comment in place rather than
spawning a new one each time. Use `--new` only when you actually want a
separate attachment thread (rare).

### First run (no existing gist)

1. Create a placeholder secret gist named `<repo>#<pr>`.
2. Clone the gist repo (authenticated with `gh auth token`).
3. Copy the passed screenshots and/or APK in. Same-basename collisions in a
   single run get suffixed `-1`, `-2`, ...
4. Commit (as `ai-tiro`) and push to whatever branch the gist's HEAD is on
   (`main` on new gists, `master` on older).
5. Build raw URLs pinned to the commit SHA (`.../raw/<sha>/<file>`) — the
   unversioned shortcut 404s for files gist considers "large" (~10 MB+,
   e.g. typical debug APKs). Verify each returns HTTP 200, retrying for a
   few seconds since raw URLs can lag a push.
6. Post one PR comment with a hidden `<!-- pr-attach:<repo>#<pr> -->` marker
   at the top, the embedded screenshots, the APK download line, and a
   footer crediting this skill. Print the gist URL + comment URL.

### Subsequent runs (gist already exists for this PR)

1. Find the gist by description (`<repo>#<pr>`) via `gh api gists --paginate`.
2. Clone it, **overwrite** passed files in place by exact basename, commit
   only if there's an actual diff, push.
3. Regenerate the comment body from the *current* contents of the gist —
   every image embedded, every APK linked, every other file linked.
   Files already in the gist that weren't passed this run stay in the
   comment.
4. Find the existing comment via the hidden marker and PATCH its body in
   place (`gh api -X PATCH repos/.../issues/comments/<id>` — `gh` CLI has no
   direct subcommand for editing issue comments).
5. If no comment with the marker exists (e.g. legacy comment), fall back to
   posting a new one.
