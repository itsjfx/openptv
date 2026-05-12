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
attach.sh <pr-number> [--apk <path>] [--screenshot <path>]... [--caption <text>]... [--repo <owner/repo>]
```

- `<pr-number>` is required.
- `--screenshot` and `--caption` are repeatable; captions match screenshots
  positionally. If a caption is omitted for a screenshot, it is derived from
  the filename (basename without extension).
- `--apk` is optional. When provided the comment includes a download link and
  the file size.
- `--repo` defaults to the current repo (`gh repo view --json nameWithOwner`).

The gist is created as **secret** and is named `<repo>#<pr-number>` (e.g.
`itsjfx/openptv#8`) so it is easy to find later.

## Behaviour summary

1. Create a placeholder gist (secret by default — `gh gist create` is secret
   unless `--public` is passed).
2. Clone the gist repo into a temp dir authenticated with `gh auth token`.
3. Copy the screenshots and/or APK in, commit, and push.
4. Build raw URLs (`https://gist.githubusercontent.com/<owner>/<id>/raw/<file>`)
   and verify each returns HTTP 200 before posting.
5. Post one PR comment combining the screenshot grid (if any) and the APK
   download line (if any). Print the gist URL and PR comment URL on success.
