#!/usr/bin/env bash

set -eu -o pipefail

# Capture a fixed set of demo/README screenshots from the app on a running emulator.
# Pins every source of run-to-run drift: device clock pinned to 12:00 Melbourne today
# (see freeze_time), SystemUI demo-mode status bar synced to that clock, fixed GPS,
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
orig_tz=""
orig_auto_tz=""
orig_auto_time=""
trap 'code="$?"; restore_time; rm -rf -- "$tmpdir"; exit "$code"' EXIT

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
  adb exec-out screencap -p > "$1"
  log "saved $1"
}

# Pin the device to ~12:00 Melbourne today so every capture reads as midday regardless of when
# the script runs. Setting the real clock works because the app passes its own "now"
# (`clock.now()`) upstream as the departures query time, so PTV serves the timetable *around the
# faked noon* and all relative times stay coherent. The clock keeps ticking from 12:00; demo_on
# re-syncs the status bar to it. Caveat: real-time estimates only exist near real wall-clock
# time, so at a faked noon stop-detail rows read "scheduled" (RelativeTimeFormatter renders
# "in N min" only for rows with a live estimate) — run near real Melbourne midday if you want
# live-estimate rows. Needs adb root, which the AOSP emulator images provide.
freeze_time() {
  local mmdd yyyy
  adb root >/dev/null
  adb wait-for-device
  [[ "$(adb shell id -u | tr -d '\r')" == 0 ]] || {
    echo "adb root unavailable — use a rootable emulator image (AOSP default, not google_apis)" >&2
    exit 1
  }
  orig_tz="$(adb shell getprop persist.sys.timezone | tr -d '\r')"
  orig_auto_tz="$(adb shell settings get global auto_time_zone | tr -d '\r')"
  orig_auto_time="$(adb shell settings get global auto_time | tr -d '\r')"
  adb shell settings put global auto_time_zone 0
  adb shell settings put global auto_time 0
  # the data is Melbourne's, so "noon" must be Melbourne noon whatever the host timezone is
  adb shell cmd alarm set-timezone Australia/Melbourne
  mmdd="$(adb shell date +%m%d | tr -d '\r')"
  yyyy="$(adb shell date +%Y | tr -d '\r')"
  adb shell date "${mmdd}1200${yyyy}.00" >/dev/null
  log "device clock pinned at $(adb shell date | tr -d '\r')"
}

restore_time() {
  [[ -n "$orig_tz" ]] || return 0
  # resync from the host clock in UTC to sidestep any host/device timezone mismatch
  adb shell date -u "$(date -u +%m%d%H%M%Y.%S)" >/dev/null
  adb shell cmd alarm set-timezone "$orig_tz"
  [[ -n "$orig_auto_tz" ]] && adb shell settings put global auto_time_zone "$orig_auto_tz"
  [[ -n "$orig_auto_time" ]] && adb shell settings put global auto_time "$orig_auto_time"
  adb unroot >/dev/null 2>&1 || true
}

demo() { adb shell am broadcast -a com.android.systemui.demo "$@" >/dev/null 2>&1; }

demo_on() {
  local hhmm
  # Freeze the status bar at the device's current local time (~12:00 once freeze_time has run),
  # not a made-up value: the app renders PTV data against the device clock, so a fake
  # status-bar time contradicts the in-app "As of HH:mm". Re-synced on every call to bound drift.
  hhmm="$(adb shell date +%H%M | tr -d '\r')"
  adb shell settings put global sysui_demo_allowed 1 >/dev/null
  demo -e command enter
  demo -e command clock -e hhmm "$hhmm"
  demo -e command battery -e level 100 -e plugged false
  demo -e command network -e wifi show -e level 4 -e fully true
  # empty datatype drops the mobile/"3G" label for a clean wifi+battery bar
  demo -e command network -e mobile show -e datatype "" -e level 4 -e fully true
  demo -e command notifications -e visible false
}

demo_off() { demo -e command exit; }

launch() { adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

# One train, one tram, one bus. Denormalised display fields mean the list renders with no
# network call; the destinationKeys match real PTV direction names so each row shows a live
# next departure. Display names are kept short so they don't truncate mid-word. Tapping the
# first row (Flinders Street, a train station) opens its stop-detail screen.
seed_favourites() {
  adb shell run-as "$pkg" sqlite3 "databases/$db_name" <<SQL
DELETE FROM favourite_destinations_at_stop;
INSERT INTO favourite_destinations_at_stop VALUES
 (1071,'sandringham','Train','Flinders Street','Melbourne City','Sandringham',-37.8183,144.9671,0,$added_at),
 (2206,'melbourne university','Tram','Bourke St Mall','Melbourne City','Melbourne University',-37.8136,144.9648,1,$added_at),
 (14163,'la trobe university','Bus','Bourke St/Queen St','Melbourne City','La Trobe University',-37.8146,144.9614,2,$added_at);
SQL
}

# Capture all three screens for one UI mode ("light" or "dark") into $out_dir/<mode>/.
# Theme defaults to "System" on a fresh install, so `cmd uimode night` flips the app.
capture_set() {
  local mode="$1" night dir
  [[ "$mode" == dark ]] && night=yes || night=no
  dir="$out_dir/$mode"
  mkdir -p "$dir"

  log "[$mode] uimode night = $night"
  adb shell cmd uimode night "$night" >/dev/null
  adb shell am force-stop "$pkg"
  demo_on # re-assert the clean status bar — a uimode change can reset SystemUI
  launch

  log "[$mode] capturing favourites"
  wait_for "to Sandringham"
  sleep 1
  screenshot "$dir/favourites.png"

  log "[$mode] capturing stop-detail (Flinders Street)"
  tap "Flinders Street · Melbourne City"
  # top-bar action; static and unique to stop-detail (the old "Routes serving this stop"
  # section no longer exists)
  wait_for "Show stop on map"
  sleep 2
  screenshot "$dir/stop-detail.png"

  log "[$mode] capturing run-pattern (first Sandringham departure)"
  # row content-desc; the favourited Sandringham group is pinned first, so this is the top row
  tap "Route Sandringham to Sandringham"
  wait_for "This stop"
  sleep 5 # let the route-line map tiles + geopath render
  screenshot "$dir/run-pattern.png"

  log "[$mode] capturing nearby map"
  adb shell input keyevent KEYCODE_BACK
  adb shell input keyevent KEYCODE_BACK
  tap "Nearby tab"
  wait_for "Nearby stops"
  # Default zoom frames ~2-3 CBD blocks with the stops nicely spread. The initial camera-idle
  # fetch populates the pins on load, so no zoom/nudge is needed.
  sleep 5 # let map tiles + stop pins render
  screenshot "$dir/nearby-map.png"
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

freeze_time
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

capture_set light
capture_set dark

adb shell cmd uimode night no >/dev/null # leave the device back in light mode
demo_off
log "done — screenshots in $out_dir/{light,dark}"
