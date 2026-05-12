#!/usr/bin/env bash
set -eu -o pipefail

usage() {
    cat >&2 <<'EOF'
usage: attach.sh <pr-number> [--apk <path>] [--screenshot <path>]... [--caption <text>]... [--repo <owner/repo>]

Upload screenshots and/or an APK to a secret gist and post a single PR comment
that embeds the screenshots and links the APK download.

Options:
  --apk <path>         APK (or other binary) to attach as a download link.
  --screenshot <path>  Screenshot to embed. Repeatable.
  --caption <text>     Caption for the corresponding screenshot. Repeatable.
                       Matched positionally; falls back to the filename stem.
  --repo <owner/repo>  Repository for the PR. Defaults to the current repo.
EOF
    exit 1
}

(( $# )) || usage

pr=""
apk=""
repo=""
screenshots=()
captions=()

while (( $# )); do
    case "$1" in
        -h|--help) usage ;;
        --apk) apk="$2"; shift 2 ;;
        --screenshot) screenshots+=("$2"); shift 2 ;;
        --caption) captions+=("$2"); shift 2 ;;
        --repo) repo="$2"; shift 2 ;;
        --) shift; break ;;
        -*)
            echo "unknown option: $1" >&2
            usage
            ;;
        *)
            if [[ -z "$pr" ]]; then
                pr="$1"
                shift
            else
                echo "unexpected positional argument: $1" >&2
                usage
            fi
            ;;
    esac
done

[[ -n "$pr" ]] || { echo "pr-number is required" >&2; usage; }
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "pr-number must be numeric, got: $pr" >&2; exit 2; }

if (( ! ${#screenshots[@]} )) && [[ -z "$apk" ]]; then
    echo "nothing to attach: pass --screenshot and/or --apk" >&2
    exit 2
fi

for f in "${screenshots[@]}"; do
    [[ -f "$f" ]] || { echo "screenshot not found: $f" >&2; exit 3; }
done
if [[ -n "$apk" ]]; then
    [[ -f "$apk" ]] || { echo "apk not found: $apk" >&2; exit 3; }
fi

if [[ -z "$repo" ]]; then
    repo="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
fi

echo "repo: $repo" >&2
echo "pr:   #$pr" >&2

gist_desc="${repo}#${pr}"
owner="$(gh api user --jq .login)"

tmpdir="$(mktemp -d)"
trap 'code="$?"; rm -rf -- "$tmpdir"; exit "$code"' EXIT

placeholder="$tmpdir/README.md"
cat >"$placeholder" <<EOF
# $gist_desc

Attachments for [$repo#$pr](https://github.com/$repo/pull/$pr).
Created by the \`pr-attach\` skill.
EOF

echo "creating secret gist..." >&2
# `gh gist create` is secret by default; omitting `--public` keeps it that way.
gist_url="$(gh gist create --desc "$gist_desc" "$placeholder")"
gist_id="${gist_url##*/}"
echo "gist: $gist_url" >&2

clone_dir="$tmpdir/gist"
token="$(gh auth token)"
git clone --quiet "https://${token}@gist.github.com/${gist_id}.git" "$clone_dir"

declare -A used_names=()
unique_name() {
    local base="$1"
    local name="$base"
    local stem="${base%.*}"
    local ext=""
    [[ "$base" == *.* ]] && ext=".${base##*.}"
    local i=1
    while [[ -n "${used_names[$name]:-}" ]]; do
        name="${stem}-${i}${ext}"
        i=$((i + 1))
    done
    used_names["$name"]=1
    printf '%s' "$name"
}

# README is already present in the gist; reserve it so we don't clobber it.
used_names["README.md"]=1

screenshot_names=()
for f in "${screenshots[@]}"; do
    name="$(unique_name "$(basename -- "$f")")"
    cp -- "$f" "$clone_dir/$name"
    screenshot_names+=("$name")
done

apk_name=""
apk_size_mb=""
if [[ -n "$apk" ]]; then
    apk_name="$(unique_name "$(basename -- "$apk")")"
    cp -- "$apk" "$clone_dir/$apk_name"
    apk_size_bytes="$(stat -c %s -- "$apk")"
    apk_size_mb="$(awk -v b="$apk_size_bytes" 'BEGIN{ printf "%.1f", b / 1048576 }')"
fi

commit_sha=""
(
    cd "$clone_dir"
    # Push to whatever the gist's default branch actually is (main on new gists,
    # master on older ones) by inspecting the existing HEAD.
    default_branch="$(git symbolic-ref --short HEAD)"
    git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" add -A
    git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" \
        commit --quiet -m "Add attachments for ${gist_desc}"
    git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" \
        push --quiet origin "HEAD:${default_branch}"
    git rev-parse HEAD >"$tmpdir/commit_sha"
)
commit_sha="$(<"$tmpdir/commit_sha")"

# Pin raw URLs to the commit SHA. Without the SHA the raw shortcut 404s for any
# file gist considers "large" (~10 MB+, e.g. typical debug APKs).
raw_base="https://gist.githubusercontent.com/${owner}/${gist_id}/raw/${commit_sha}"

verify_url() {
    local url="$1"
    local label="$2"
    local status
    # Retry a few times: gist raw URLs can lag a second or two behind a push.
    local tries=10
    while (( tries > 0 )); do
        status="$(curl -sI -o /dev/null -w '%{http_code}' "$url" || true)"
        if [[ "$status" == "200" ]]; then
            return 0
        fi
        tries=$((tries - 1))
        sleep 1
    done
    echo "verification failed for $label: $url (last status: $status)" >&2
    exit 4
}

body_file="$tmpdir/body.md"
: >"$body_file"

if (( ${#screenshot_names[@]} )); then
    for i in "${!screenshot_names[@]}"; do
        name="${screenshot_names[$i]}"
        url="${raw_base}/${name}"
        verify_url "$url" "$name"
        caption="${captions[$i]:-}"
        if [[ -z "$caption" ]]; then
            stem="${name%.*}"
            caption="$stem"
        fi
        printf '![%s](%s)\n\n' "$caption" "$url" >>"$body_file"
    done
fi

if [[ -n "$apk_name" ]]; then
    apk_url="${raw_base}/${apk_name}"
    verify_url "$apk_url" "$apk_name"
    printf '**APK:** [%s](%s) (%s MB)\n' "$apk_name" "$apk_url" "$apk_size_mb" >>"$body_file"
fi

printf '\n<sub>Uploaded via [`pr-attach`](https://gist.github.com/%s/%s).</sub>\n' \
    "$owner" "$gist_id" >>"$body_file"

echo "posting PR comment..." >&2
comment_url="$(gh pr comment "$pr" --repo "$repo" --body-file "$body_file")"

echo "gist:    $gist_url"
echo "comment: $comment_url"
