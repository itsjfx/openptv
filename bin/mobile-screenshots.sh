#!/usr/bin/env bash

set -eu -o pipefail

# Capture a fixed set of demo/README screenshots from the app on a running emulator.
# Pins every source of run-to-run drift: SystemUI demo-mode status bar, fixed GPS,
# pre-granted location permission, and DB-seeded favourites (so the favourites list
# doesn't depend on live departures). Navigation is by accessibility element, not
# fixed coordinates, so it survives resolution/layout changes.
#
# Requires a running adb device (boot the pixel_api36 AOSP AVD first). No GMS needed.

cd "$(dirname "$0")/.."

pkg="ac.jfx.openptv"
db_name="openptv.db"
apk="mobile/app/build/outputs/apk/debug/app-debug.apk"
out_dir="mobile/build/screenshots"
skip_build=0

# Melbourne CBD. emu geo fix wants "lng lat"; the map fetches around wherever it lands.
geo_lng=144.9631
geo_lat=-37.8136

# Fixed so seeded rows are byte-identical across runs (value isn't shown in the UI).
added_at=1718200000000

usage() {
  cat >&2 <<EOF
usage: bin/mobile-screenshots.sh [--skip-build] [--apk PATH] [--out DIR] [--device SERIAL]

  --skip-build     don't run ./gradlew :app:assembleDebug, use an existing APK
  --apk PATH       APK to install (default: $apk)
  --out DIR        output directory for PNGs (default: $out_dir)
  --device SERIAL  target a specific adb device (else the only attached one)
EOF
  exit 2
}

while (( $# )); do
  case "$1" in
    --skip-build) skip_build=1 ;;
    --apk) shift; apk="${1:?--apk needs a path}" ;;
    --out) shift; out_dir="${1:?--out needs a dir}" ;;
    --device|-s) shift; export ANDROID_SERIAL="${1:?--device needs a serial}" ;;
    -h|--help) usage ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
  shift || true
done

command -v adb &>/dev/null || { echo "adb not found on PATH" >&2; exit 1; }
command -v python3 &>/dev/null || { echo "python3 not found on PATH" >&2; exit 1; }

tmpdir="$(mktemp -d)"
trap 'code="$?"; rm -rf -- "$tmpdir"; exit "$code"' EXIT

log() { echo "==> $*" >&2; }

# Dump the current accessibility tree to a local file we can query. uiautomator
# transiently returns a null root during launches/animations — stay non-fatal and
# let wait_for keep polling rather than tripping set -e.
ui_dump() {
  adb shell uiautomator dump /sdcard/openptv-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/openptv-ui.xml 2>/dev/null | tr -d '\r' > "$tmpdir/ui.xml" || true
}

# Centre "x y" of the first node whose text or content-desc matches $1 (exact preferred).
node_xy() {
  python3 -c '
import sys, xml.etree.ElementTree as ET
needle, path = sys.argv[1], sys.argv[2]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
best = None
for n in root.iter("node"):
    t, cd = n.get("text", ""), n.get("content-desc", "")
    exact = needle in (t, cd)
    if exact or (t and needle in t) or (cd and needle in cd):
        x1, y1, x2, y2 = (int(v) for v in n.get("bounds").translate(str.maketrans("[],", "   ")).split())
        cand = ((x1 + x2) // 2, (y1 + y2) // 2, exact)
        if best is None or (exact and not best[2]):
            best = cand
if best:
    print(best[0], best[1])
' "$1" "$tmpdir/ui.xml"
}

# Poll the UI until an element appears (or time out and fail loudly).
wait_for() {
  local needle="$1" timeout="${2:-20}" waited=0
  while (( waited < timeout )); do
    ui_dump
    [[ -n "$(node_xy "$needle")" ]] && return 0
    sleep 1
    (( waited += 1 ))
  done
  echo "timed out waiting for UI element: $needle" >&2
  return 1
}

tap() {
  local needle="$1" xy
  wait_for "$needle" "${2:-20}"
  xy="$(node_xy "$needle")"
  log "tap '$needle' -> $xy"
  adb shell input tap $xy
}

screenshot() {
  adb exec-out screencap -p > "$out_dir/$1.png"
  log "saved $out_dir/$1.png"
}

demo() { adb shell am broadcast -a com.android.systemui.demo "$@" >/dev/null 2>&1; }

demo_on() {
  adb shell settings put global sysui_demo_allowed 1 >/dev/null
  demo -e command enter
  demo -e command clock -e hhmm 1000
  demo -e command battery -e level 100 -e plugged false
  demo -e command network -e wifi show -e level 4 -e fully true
  # empty datatype drops the mobile/"3G" label for a clean wifi+battery bar
  demo -e command network -e mobile show -e datatype "" -e level 4 -e fully true
  demo -e command notifications -e visible false
}

demo_off() { demo -e command exit; }

launch() { adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

# Denormalised display fields mean these render with no network call. Tapping the first
# row (Flinders Street, a train station) opens its stop-detail screen.
seed_favourites() {
  adb shell run-as "$pkg" sqlite3 "databases/$db_name" <<SQL
DELETE FROM favourite_destinations_at_stop;
INSERT INTO favourite_destinations_at_stop VALUES
 (1071,'pakenham','Train','Flinders Street','Melbourne City','Pakenham',-37.8183,144.9671,0,$added_at),
 (1235,'sunbury','Train','Town Hall','Melbourne City','Sunbury',-37.8136,144.9665,1,$added_at),
 (2722,'box hill','Tram','Flinders St Station/Elizabeth St #1','Melbourne City','Box Hill',-37.8175,144.9655,2,$added_at);
SQL
}

adb get-state &>/dev/null || {
  echo "no adb device — boot an emulator first (e.g. the pixel_api36 AOSP AVD)" >&2
  exit 1
}

if (( ! skip_build )); then
  log "building debug APK"
  ( cd mobile && ./gradlew :app:assembleDebug ) >&2
fi
[[ -f "$apk" ]] || { echo "APK not found: $apk (build it or pass --apk)" >&2; exit 1; }
mkdir -p "$out_dir"

log "installing app"
adb install -r "$apk" >&2

# Clean slate so no stale favourites / filters leak in, then re-grant (pm clear drops grants).
adb shell pm clear "$pkg" >/dev/null
adb shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION
adb emu geo fix "$geo_lng" "$geo_lat"

demo_on

log "first launch to create the database"
launch
# First-run "Choose your server" picker (pm clear resets it). Default is pre-selected.
if wait_for "Continue" 15; then
  tap "Continue"
fi
wait_for "Favourites tab"
adb shell am force-stop "$pkg"

log "seeding favourites"
seed_favourites

log "relaunching with seeded favourites"
launch

log "capturing favourites"
wait_for "to Pakenham"
sleep 1
screenshot favourites

log "capturing stop-detail (Flinders Street)"
tap "Flinders Street · Melbourne City"
wait_for "Routes serving this stop"
sleep 2
screenshot stop-detail

log "capturing nearby map"
adb shell input keyevent KEYCODE_BACK
tap "Nearby tab"
wait_for "Nearby stops"
sleep 3 # initial camera-idle fetch + tiles
# Zoom out for a wider CBD spread, then nudge: scroll-wheel zoom alone doesn't fire
# MapLibre's onCameraIdle, so a tiny drag is needed to trigger the re-fetch.
adb shell input mouse scroll 540 1100 --axis VSCROLL,-2 || true
sleep 1
adb shell input swipe 540 1100 548 1108 250
sleep 4 # let tiles + stop pins render after the re-fetch
screenshot nearby-map

demo_off
log "done — screenshots in $out_dir"
