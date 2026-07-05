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
#   3. installs the app, sets "Dresden Hbf" as home station via the
#      real settings UI, imports scripts/demo-route.gpx (a 26.4 km
#      Dresden → Meißen ride along the Elbe, generated once with the
#      public BRouter API) through the system file picker
#   4. captures the seven screens (light + dark) with adb screencap
#      and copies them to fastlane/metadata/android/en-US/images/
#      phoneScreenshots/{1..7}.png
#
# Requirements / caveats:
#   - /dev/kvm must be available (hardware acceleration)
#   - network access: station discovery + connections come from the
#     live Deutsche Bahn API, map tiles from OSM. Departure times in
#     the screenshots are whatever is real at run time.
#   - The UI is driven by tap coordinates recorded at 1080x2400. If a
#     screen's layout changes materially, update the coordinates in
#     the "drive the UI" section below (screencap between steps and
#     inspect out/debug-*.png to re-anchor them).
#   - Flaky network can make a sheet come up empty; just re-run.
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/fastlane/metadata/android/en-US/images/phoneScreenshots"
GPX="$REPO/scripts/demo-route.gpx"
APK="$REPO/app/build/outputs/apk/foss/debug/app-foss-debug.apk"
PKG="dev.gpxit.app"

WORK="$(mktemp -d -t gpxit-screenshots-XXXXXX)"
export ANDROID_AVD_HOME="$WORK/avd"
mkdir -p "$ANDROID_AVD_HOME" "$WORK/shots"

: "${ANDROID_SDK_ROOT:?run inside 'nix develop .#screenshots'}"
EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
[ -x "$EMULATOR" ] || { echo "emulator not found at $EMULATOR — wrong dev shell?"; exit 1; }
[ -e /dev/kvm ] || { echo "/dev/kvm missing — emulator would be unusably slow"; exit 1; }
command -v adb >/dev/null || { echo "adb not on PATH"; exit 1; }
command -v avdmanager >/dev/null || { echo "avdmanager not on PATH"; exit 1; }

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

tap()  { adb shell input tap "$1" "$2"; sleep "${3:-1.5}"; }
back() { adb shell input keyevent 4; sleep 1.5; }

# ── 1. Build the APK ─────────────────────────────────────────────
log "Building fossDebug APK"
(cd "$REPO" && ./gradlew --console=plain -q assembleFossDebug)

# ── 2. AVD + emulator ────────────────────────────────────────────
log "Creating AVD"
echo no | avdmanager create avd -n screenshots \
  -k "system-images;android-35;default;x86_64" --force >/dev/null
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
# All coordinates are for 1080x2400. Sleeps are generous because the
# transit queries hit the live DB API.
log "Setting home station (Dresden Hbf)"
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 5
tap 996 137            # settings gear
tap 540 394            # expand "Home" section
tap 540 683 1          # focus station search field
adb shell input text "Dresden%sHbf"; sleep 5   # live suggestions
tap 540 852 2          # first suggestion: Dresden Hbf
back                   # hide keyboard
back                   # back to home screen

log "Importing demo route via file picker"
tap 290 2147 3         # "GPX file" button → DocumentsUI
tap 73 126 2           # drawer
tap 258 578 2          # Downloads
tap 296 912 1          # dresden-meissen.gpx
sleep 20               # import + station discovery (live API)
shot 1-home-light

log "Map view"
tap 540 1754 1         # "View on Map"
sleep 10               # tiles
shot 2-map-light

log "Take me home sheet"
tap 142 2160 1         # bottom nav: Take me home
sleep 25               # connection queries (live API)
shot 3-takemehome-light

log "Station detail"
tap 198 1736 1         # best-station card
sleep 12               # departures (live API)
shot 4-station-light

log "Fullscreen timeline"
back                   # close station detail
tap 142 2160 1; sleep 6   # reopen sheet (cached)
tap 1002 1463 3        # expand chevron
shot 5-timeline-light

log "Dark mode"
back; back             # back to plain map
adb shell cmd uimode night yes; sleep 2
# restart so no leftover sheet labels sit on the map
adb shell am force-stop "$PKG"; sleep 2
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 8
shot 6-home-dark
tap 540 1754 1         # "View on Map"
sleep 12
shot 7-map-dark
adb shell cmd uimode night no || true

# ── 5. Install into fastlane ─────────────────────────────────────
log "Copying screenshots to $OUT"
mkdir -p "$OUT"
i=1
for f in 1-home-light 2-map-light 3-takemehome-light 4-station-light \
         5-timeline-light 6-home-dark 7-map-dark; do
  cp "$WORK/shots/$f.png" "$OUT/$i.png"
  i=$((i+1))
done
ls -la "$OUT"
log "Done — review the PNGs before committing"
