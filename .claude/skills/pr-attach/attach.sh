#!/usr/bin/env bash
set -eu -o pipefail

usage() {
    cat >&2 <<'EOF'
usage: attach.sh <pr-number> [--apk <path>] [--screenshot <path>]... [--caption <text>]... [--repo <owner/repo>] [--new]

Upload screenshots and/or an APK to a per-PR secret gist and post (or update)
a single PR comment that embeds the screenshots and links the APK download.

By default the script reuses an existing pr-attach gist for the same PR
(matched by description "<repo>#<pr>") and edits the existing comment in
place. Passed files overwrite same-named files in the gist; files already
present that aren't passed this run remain in the gist and the comment.

Options:
  --apk <path>         APK (or any binary) to link as a download.
  --screenshot <path>  Screenshot to embed. Repeatable.
  --caption <text>     Caption for the matching screenshot. Not persisted
                       across updates; defaults to the filename stem.
  --repo <owner/repo>  Repository. Defaults to the current repo.
  --new                Force a fresh gist + new comment even if a pr-attach
                       gist already exists for this PR.
EOF
    exit 1
}

(( $# )) || usage

pr=""
apk=""
repo=""
force_new=false
screenshots=()
captions=()

while (( $# )); do
    case "$1" in
        -h|--help) usage ;;
        --apk) apk="$2"; shift 2 ;;
        --screenshot) screenshots+=("$2"); shift 2 ;;
        --caption) captions+=("$2"); shift 2 ;;
        --repo) repo="$2"; shift 2 ;;
        --new) force_new=true; shift ;;
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

if (( ${#screenshots[@]} == 0 )) && [[ -z "$apk" ]]; then
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

owner="$(gh api user --jq .login)"
gist_desc="${repo}#${pr}"
# Hidden marker injected at the top of the PR comment so we can find our own
# comment on subsequent invocations and edit it in place.
marker="<!-- pr-attach:${gist_desc} -->"

echo "repo: $repo" >&2
echo "pr:   #$pr" >&2

tmpdir="$(mktemp -d)"
trap 'code="$?"; rm -rf -- "$tmpdir"; exit "$code"' EXIT

existing_gist_id=""
if ! $force_new; then
    existing_gist_id="$(gh api gists --paginate \
        --jq ".[] | select(.description == \"$gist_desc\") | .id" \
        | head -n1 || true)"
fi

if [[ -n "$existing_gist_id" ]]; then
    gist_id="$existing_gist_id"
    gist_url="https://gist.github.com/${owner}/${gist_id}"
    mode="update"
    echo "reusing gist: $gist_url" >&2
else
    placeholder="$tmpdir/README.md"
    cat >"$placeholder" <<EOF
# $gist_desc

Attachments for [$repo#$pr](https://github.com/$repo/pull/$pr).
Created by the \`pr-attach\` skill.
EOF
    echo "creating secret gist..." >&2
    # gh gist create is secret unless --public is passed.
    gist_url="$(gh gist create --desc "$gist_desc" "$placeholder")"
    gist_id="${gist_url##*/}"
    mode="new"
fi

clone_dir="$tmpdir/gist"
token="$(gh auth token)"
git clone --quiet "https://${token}@gist.github.com/${gist_id}.git" "$clone_dir"

# In update mode, overwrite same-named files in the gist. In new mode, generate
# unique names so two files with the same basename passed in one run don't
# collide.
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
used_names["README.md"]=1

declare -A caption_for=()

place_file() {
    local src="$1"
    local caption="${2:-}"
    local dest
    if [[ "$mode" == "update" ]]; then
        dest="$(basename -- "$src")"
    else
        dest="$(unique_name "$(basename -- "$src")")"
    fi
    cp -f -- "$src" "$clone_dir/$dest"
    [[ -n "$caption" ]] && caption_for["$dest"]="$caption"
}

for i in "${!screenshots[@]}"; do
    place_file "${screenshots[$i]}" "${captions[$i]:-}"
done
if [[ -n "$apk" ]]; then
    place_file "$apk"
fi

commit_sha=""
(
    cd "$clone_dir"
    # Push to whatever branch the gist's HEAD is on (main on new gists,
    # master on older ones).
    default_branch="$(git symbolic-ref --short HEAD)"
    git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" add -A
    if git diff --cached --quiet; then
        # Identical content re-uploaded — nothing to push.
        git rev-parse HEAD >"$tmpdir/commit_sha"
    else
        git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" \
            commit --quiet -m "Attachments for ${gist_desc}"
        git -c user.email="ai-tiro@jfx.ac" -c user.name="ai-tiro" \
            push --quiet origin "HEAD:${default_branch}"
        git rev-parse HEAD >"$tmpdir/commit_sha"
    fi
)
commit_sha="$(<"$tmpdir/commit_sha")"

# Pin raw URLs to the commit SHA. Without the SHA the unversioned raw URL
# 404s for files gist considers "large" (~10 MB+, e.g. typical debug APKs).
raw_base="https://gist.githubusercontent.com/${owner}/${gist_id}/raw/${commit_sha}"

verify_url() {
    local url="$1"
    local label="$2"
    local status
    # Retry: gist raw URLs can lag a second or two behind a push.
    local tries=10
    while (( tries > 0 )); do
        status="$(curl -sI -o /dev/null -w '%{http_code}' "$url" || true)"
        [[ "$status" == "200" ]] && return 0
        tries=$((tries - 1))
        sleep 1
    done
    echo "verification failed for $label: $url (last status: $status)" >&2
    exit 4
}

# Build the comment body from the *current* contents of the gist so the body
# stays in sync with reality across update runs. Bash globs are alphabetical
# in the default locale, so per-bucket order is deterministic without sort.
images=()
apks=()
others=()
shopt -s nullglob
for path in "$clone_dir"/*; do
    name="$(basename -- "$path")"
    [[ "$name" == "README.md" ]] && continue
    case "${name,,}" in
        *.png|*.jpg|*.jpeg|*.gif|*.webp) images+=("$name") ;;
        *.apk) apks+=("$name") ;;
        *) others+=("$name") ;;
    esac
done
shopt -u nullglob

body_file="$tmpdir/body.md"
{
    printf '%s\n\n' "$marker"

    for name in ${images[@]+"${images[@]}"}; do
        url="${raw_base}/${name}"
        verify_url "$url" "$name"
        caption="${caption_for[$name]:-${name%.*}}"
        printf '![%s](%s)\n\n' "$caption" "$url"
    done

    for name in ${apks[@]+"${apks[@]}"}; do
        url="${raw_base}/${name}"
        verify_url "$url" "$name"
        size_bytes="$(stat -c %s -- "$clone_dir/$name")"
        size_mb="$(awk -v b="$size_bytes" 'BEGIN{ printf "%.1f", b / 1048576 }')"
        printf '**APK:** [%s](%s) (%s MB)\n\n' "$name" "$url" "$size_mb"
    done

    for name in ${others[@]+"${others[@]}"}; do
        url="${raw_base}/${name}"
        verify_url "$url" "$name"
        printf '[%s](%s)\n\n' "$name" "$url"
    done

    printf '<sub>Uploaded via [`pr-attach`](https://gist.github.com/%s/%s).</sub>\n' \
        "$owner" "$gist_id"
} >"$body_file"

# Find an existing pr-attach comment via the marker so we can PATCH it in
# place. gh CLI has no subcommand for editing issue comments; the raw API does.
existing_comment_id=""
if [[ "$mode" == "update" ]]; then
    existing_comment_id="$(gh api --paginate "repos/${repo}/issues/${pr}/comments" \
        --jq ".[] | select(.body | contains(\"$marker\")) | .id" \
        | head -n1 || true)"
fi

if [[ -n "$existing_comment_id" ]]; then
    echo "updating PR comment $existing_comment_id..." >&2
    gh api -X PATCH "repos/${repo}/issues/comments/${existing_comment_id}" \
        -f "body=@${body_file}" >/dev/null
    comment_url="https://github.com/${repo}/pull/${pr}#issuecomment-${existing_comment_id}"
else
    echo "posting PR comment..." >&2
    comment_url="$(gh pr comment "$pr" --repo "$repo" --body-file "$body_file")"
fi

echo "gist:    $gist_url"
echo "comment: $comment_url"
