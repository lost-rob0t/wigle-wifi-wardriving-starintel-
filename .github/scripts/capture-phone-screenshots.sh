#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots diagnostics
PHONE_APK="$(find artifacts -type f -name 'wiglewifiwardriving-debug.apk' -print -quit)"
if [[ -z "$PHONE_APK" ]]; then
  echo "Phone APK not found. Artifact contents:"
  find artifacts -maxdepth 8 -type f -print
  exit 1
fi

adb wait-for-device
adb install -r "$PHONE_APK"
adb shell svc wifi enable || true

for permission in \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.POST_NOTIFICATIONS \
  android.permission.BLUETOOTH_SCAN \
  android.permission.BLUETOOTH_CONNECT \
  android.permission.READ_PHONE_STATE; do
  adb shell pm grant net.wigle.wigleandroid "$permission" || true
done

focused_window() {
  adb shell dumpsys window windows 2>/dev/null \
    | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true
}

resumed_activity() {
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
}

fail_capture() {
  local name="$1"
  echo "::error::Visual CI did not render expected phone screen: $name"
  echo "Resumed activity: $(resumed_activity)"
  echo "Focused window: $(focused_window)"
  adb shell dumpsys activity activities > "diagnostics/phone-${name}-activity.txt" || true
  adb shell dumpsys window windows > "diagnostics/phone-${name}-window.txt" || true
  adb logcat -d -v threadtime > "diagnostics/phone-${name}-logcat.txt" || true
  adb exec-out screencap -p > "screenshots/FAILED-phone-${name}.png" || true
  echo "----- app/runtime crash excerpt -----"
  grep -E -A35 -B8 'FATAL EXCEPTION|AndroidRuntime|Process: net\.wigle|Caused by:|am_crash|Force finishing|WigleUncaughtExceptionHandler|Most Recent Error Report' \
    "diagnostics/phone-${name}-logcat.txt" | tail -n 260 || true
  echo "----- process / task state -----"
  grep -E 'net\.wigle\.wigleandroid|mResumedActivity|topResumedActivity|mCurrentFocus|mFocusedApp' \
    "diagnostics/phone-${name}-activity.txt" "diagnostics/phone-${name}-window.txt" | tail -n 120 || true
  exit 1
}

wait_for_component() {
  local name="$1"
  local component="$2"
  local i resumed focused
  for i in $(seq 1 30); do
    resumed="$(resumed_activity)"
    focused="$(focused_window)"
    if [[ "$resumed" == *"$component"* ]] && [[ "$focused" == *"net.wigle.wigleandroid"* ]]; then
      return 0
    fi
    sleep 0.5
  done
  fail_capture "$name"
}

capture_main() {
  local screen="$1"

  adb logcat -c || true
  adb shell am force-stop net.wigle.wigleandroid
  adb shell am start -W \
    -n net.wigle.wigleandroid/.MainActivity \
    --ez ci_visual true \
    --es ci_visual_screen "$screen"

  wait_for_component "$screen" "net.wigle.wigleandroid/.MainActivity"
  sleep 1
  adb exec-out screencap -p > "screenshots/phone-${screen}.png"
  test -s "screenshots/phone-${screen}.png"
}

capture_main list
capture_main dash
capture_main starintel
capture_main map

adb logcat -c || true
adb shell am force-stop net.wigle.wigleandroid
adb shell am start -W \
  -n net.wigle.wigleandroid/.MacFilterActivity \
  --es net.wigle.wigleandroid.filter.MESSAGE alertFilter \
  --ez ci_visual true

wait_for_component "watchlist" "net.wigle.wigleandroid/.MacFilterActivity"
sleep 1
adb exec-out screencap -p > screenshots/phone-watchlist.png
test -s screenshots/phone-watchlist.png

echo "Phone screenshots:"
ls -lh screenshots/phone-*.png
