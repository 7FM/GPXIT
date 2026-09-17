#!/usr/bin/env bash
#
# Regenerate the F-Droid / fastlane phone screenshots by driving the
# real app in a headless Android emulator.
#
# Usage:
#   nix develop .#screenshots --command ./scripts/generate-screenshots.sh
#
# What it does:
#   1. builds the fossDebug APK
#   2. creates a throwaway 1080x2400 AVD and boots it headless (KVM)
#   3. installs the app, sets "Dresden Hbf" as home station and keeps the
#      German POI data via the real settings UI, imports
#      scripts/demo-route.gpx (a 26.4 km Dresden → Meißen ride along the
#      Elbe, generated once with the public BRouter API) through the
#      system file picker
#   4. captures nine screens (light + dark) with adb screencap and copies
#      them to fastlane/metadata/android/en-US/images/phoneScreenshots/
#      {1..9}.png
#
# Requirements / caveats:
#   - /dev/kvm must be available (hardware acceleration)
#   - network access: station discovery + connections come from the
#     live Deutsche Bahn API, map tiles from OSM, the POI data from the
#     `poi-data` release. Departure times and opening states in the
#     screenshots are whatever is real at run time — run it while
#     shops are open: one of them has to show "Open · closes …".
#   - Buttons are found by their text / accessibility label
#     (scripts/screenshot_ui.py); a few spots without one are tapped by
#     coordinates recorded at 1080x2400. If a screen's layout changes
#     materially, update those (screencap between steps to re-anchor
#     them). A failed lookup saves the screen to $DEBUG_DIR.
#   - Flaky network can make a sheet come up empty; just re-run.
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/fastlane/metadata/android/en-US/images/phoneScreenshots"
GPX="$REPO/scripts/demo-route.gpx"
APK="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug.apk"
UI="$REPO/scripts/screenshot_ui.py"
PKG="dev.gpxit.app"
# What the app downloads its POI datasets from.
POI_INDEX_URL="${POI_INDEX_URL:-https://github.com/7FM/GPXIT/releases/download/poi-data/pois-index.json}"
DEBUG_DIR="${DEBUG_DIR:-$REPO/app/build/screenshots-debug}"

WORK="$(mktemp -d -t gpxit-screenshots-XXXXXX)"
export ANDROID_AVD_HOME="$WORK/avd"
mkdir -p "$ANDROID_AVD_HOME" "$WORK/shots"

: "${ANDROID_SDK_ROOT:?run inside 'nix develop .#screenshots'}"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
[ -x "$EMULATOR" ] || { echo "emulator not found at $EMULATOR — wrong dev shell?"; exit 1; }
[ -e /dev/kvm ] || { echo "/dev/kvm missing — emulator would be unusably slow"; exit 1; }
for tool in adb avdmanager python3; do
  command -v "$tool" >/dev/null || { echo "$tool not on PATH"; exit 1; }
done
curl -fsSL -o /dev/null "$POI_INDEX_URL" || {
  echo "no POI datasets at $POI_INDEX_URL — run the Build POI Dataset workflow first"; exit 1; }

EMU_PID=""
cleanup() {
  adb emu kill >/dev/null 2>&1 || true
  [ -n "$EMU_PID" ] && wait "$EMU_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

log() { printf '\n== %s\n' "$*"; }

shot() { # shot <name> — capture the current screen
  adb exec-out screencap -p > "$WORK/shots/$1.png"
  echo "   captured $1"
}

fail() { # fail <message> — keep the current screen for debugging, then stop
  mkdir -p "$DEBUG_DIR"
  adb exec-out screencap -p > "$DEBUG_DIR/failed.png" || true
  cp "$WORK/ui.xml" "$DEBUG_DIR/failed.xml" 2>/dev/null || true
  echo "$1 (screen saved to $DEBUG_DIR/failed.png)"
  exit 1
}

tap()  { adb shell input tap "$1" "$2"; sleep "${3:-1.5}"; }
back() { adb shell input keyevent 4; sleep 1.5; }

dump() { # dump — current UI hierarchy to $WORK/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml > "$WORK/ui.xml" 2>/dev/null || true
}

on_screen() { # on_screen <text> — whether an element shows <text>
  dump
  python3 "$UI" find-text "$WORK/ui.xml" "$1" >/dev/null
}

tap_text() { # tap_text <text> [n] [sleep] — tap the n-th element showing <text>
  local xy="" _
  for _ in $(seq 1 20); do   # give slow screens up to ~20 s to show it
    dump
    xy="$(python3 "$UI" find-text "$WORK/ui.xml" "$1" "${2:-0}")" && break
    sleep 1
  done
  [ -n "$xy" ] || fail "'$1' not on screen"
  # shellcheck disable=SC2086 # "x y"
  tap $xy "${3:-1.5}"
}

tap_field() { # tap_field [n] — focus the n-th text field
  local xy
  dump
  xy="$(python3 "$UI" find-field "$WORK/ui.xml" "${1:-0}")" || fail "no text field on screen"
  # shellcheck disable=SC2086
  tap $xy 1
}

hide_keyboard() {
  if adb shell dumpsys input_method | grep -q "mInputShown=true"; then back; fi
}

# ── 1. Build the APK ─────────────────────────────────────────────
log "Building fossDebug APK"
# No daemon: its memory is better spent on the emulator.
(cd "$REPO" && ./gradlew --no-daemon --console=plain -q assembleFossDebug)

# ── 2. AVD + emulator ────────────────────────────────────────────
# Must match the system image flake.nix provides for this shell.
log "Creating AVD"
echo no | avdmanager create avd -n screenshots \
  -k "system-images;android-37.0;google_apis;x86_64" --force >/dev/null
cat >> "$ANDROID_AVD_HOME/screenshots.avd/config.ini" <<'EOF'
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.ramSize=2048
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
EOF

log "Booting emulator (headless)"
"$EMULATOR" -avd screenshots -no-window -no-audio -no-boot-anim \
  -no-snapshot -gpu swiftshader_indirect > "$WORK/emulator.log" 2>&1 &
EMU_PID=$!

adb wait-for-device
until adb shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 3; done
sleep 5
adb shell wm size | grep -q "1080x2400" || {
  echo "unexpected screen size:"; adb shell wm size; exit 1; }

# ── 3. Install + seed data ───────────────────────────────────────
log "Installing app"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -g "$APK" >/dev/null
adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb push "$GPX" /sdcard/Download/dresden-meissen.gpx >/dev/null
# GPS fix on the route between Radebeul and Coswig
adb emu geo fix 13.6480 51.1010 >/dev/null

# ── 4. Drive the UI ──────────────────────────────────────────────
# Sleeps are generous because the transit queries hit the live DB API.
log "Setting home station (Dresden Hbf)"
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 5
tap_text "Settings"
tap_text "Home"            # expand the section
tap_field                  # station search
adb shell input text "Dresden%sHbf"; sleep 5   # live suggestions
tap_text "Dresden Hbf" 1 2 # first suggestion (0 is the search field)
hide_keyboard

log "Keeping the German POI data"
tap_text "Map & data"
adb shell input swipe 540 1900 540 700 500; sleep 1
tap_field                  # country search
adb shell input text "Germany"; sleep 1
hide_keyboard
tap_text "Germany" 1       # the country row (0 is the search field)
for _ in $(seq 1 90); do   # ~20 MB from the release
  adb shell run-as "$PKG" ls files/pois 2>/dev/null | grep -qx "germany.db" && break
  sleep 2
done
adb shell run-as "$PKG" ls files/pois 2>/dev/null | grep -qx "germany.db" \
  || fail "the German POI data wasn't installed"
back                       # back to home screen

log "Importing demo route via file picker"
tap_text "GPX file" 0 3    # → DocumentsUI
if ! on_screen "dresden-meissen.gpx"; then
  tap_text "Show roots" 0 2
  tap_text "Downloads" 0 2
fi
tap_text "dresden-meissen.gpx" 0 1
sleep 20                   # import + station discovery (live API)
shot home-light

log "Map view"
tap_text "View on Map" 0 1
sleep 10                   # tiles
shot map-light

log "Take me home sheet"
tap_text "Take me home" 0 1
sleep 25                   # connection queries (live API)
shot takemehome-light

log "Station detail"
tap 198 1736 1             # best-station card
sleep 12                   # departures (live API)
shot station-light

log "Fullscreen timeline"
back                       # close station detail
tap_text "Take me home" 0 6   # reopen sheet (cached)
tap_text "Expand" 0 3
shot timeline-light

log "Opening hours of a shop"
back; back                 # back to plain map
tap_text "Layers"
tap_text "Grocery / bakery"
tap_text "✕"
tap_text "My location" 0 8 # centre on the GPS fix, zoom 16; tiles
found=""
for attempt in 1 2; do     # if no open shop is in view, look a bit wider
  adb exec-out screencap -p > "$WORK/map.png"
  while read -r x y; do    # open shops, nearest to the centre first
    tap "$x" "$y" 2
    dump
    if python3 "$UI" has-text "$WORK/ui.xml" '^Open · closes '; then
      found=1
      break 2
    fi
    tap "$x" "$y" 1        # no opening hours: close the bubble again
  done < <(python3 "$UI" open-pois "$WORK/map.png")
  if [ "$attempt" = 1 ]; then tap_text "Zoom out" 0 8; fi
done
[ -n "$found" ] || fail "no shop on the map that is open and closes later today"
shot openinghours-light
tap_text "Layers"
tap_text "Grocery / bakery"   # off again, so the dark map stays uncluttered
tap_text "✕"

log "POI data settings"
back                       # back to home screen
tap_text "Settings"
tap_text "Map & data"
adb shell input swipe 540 1900 540 700 500; sleep 1
shot poidata-light
back

log "Dark mode"
adb shell cmd uimode night yes; sleep 2
# restart so no leftover sheet labels sit on the map
adb shell am force-stop "$PKG"; sleep 2
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 8
shot home-dark
tap_text "View on Map" 0 1
sleep 12
shot map-dark
adb shell cmd uimode night no || true

# ── 5. Install into fastlane ─────────────────────────────────────
log "Copying screenshots to $OUT"
mkdir -p "$OUT"
i=1
for f in home-light map-light openinghours-light takemehome-light station-light \
         timeline-light poidata-light home-dark map-dark; do
  cp "$WORK/shots/$f.png" "$OUT/$i.png"
  i=$((i+1))
done
ls -la "$OUT"
log "Done — review the PNGs before committing"
