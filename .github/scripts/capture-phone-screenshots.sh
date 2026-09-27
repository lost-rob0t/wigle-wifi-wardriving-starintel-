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

dump_ui() {
  adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/window.xml 2>/dev/null || true
}

resumed_activity() {
  {
    adb shell dumpsys activity activities 2>/dev/null \
      | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
    adb shell dumpsys window windows 2>/dev/null \
      | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true
  } | tr '\n' ' '
}

fail_capture() {
  local name="$1"
  echo "::error::Visual CI did not render expected phone screen: $name"
  echo "Resumed activity: $(resumed_activity)"
  dump_ui > "diagnostics/phone-${name}-ui.xml" || true
  adb logcat -d -v threadtime > "diagnostics/phone-${name}-logcat.txt" || true
  adb shell dumpsys activity activities > "diagnostics/phone-${name}-activity.txt" || true
  adb shell dumpsys window windows > "diagnostics/phone-${name}-window.txt" || true
  adb exec-out screencap -p > "screenshots/FAILED-phone-${name}.png" || true
  echo "----- app/runtime crash excerpt -----"
  grep -E -A35 -B8 'FATAL EXCEPTION|AndroidRuntime|Process: net\.wigle|Caused by:|am_crash|Force finishing|WigleUncaughtExceptionHandler|Most Recent Error Report' \
    "diagnostics/phone-${name}-logcat.txt" | tail -n 260 || true
  echo "----- process / task state -----"
  grep -E 'net\.wigle\.wigleandroid|mResumedActivity|topResumedActivity|mCurrentFocus|mFocusedApp' \
    "diagnostics/phone-${name}-activity.txt" "diagnostics/phone-${name}-window.txt" | tail -n 120 || true
  exit 1
}

wait_for_screen() {
  local name="$1"
  local component="$2"
  local needle="$3"
  local i current ui

  for i in $(seq 1 30); do
    current="$(resumed_activity)"
    ui="$(dump_ui)"

    if [[ "$current" == *"$component"* ]] \
      && [[ "$ui" == *"$needle"* ]] \
      && [[ "$ui" != *"Most Recent Error Report"* ]] \
      && [[ "$ui" != *"Starting..."* ]] \
      && [[ "$ui" != *"Allow "* ]] \
      && [[ "$ui" != *"permission"* ]]; then
      return 0
    fi
    sleep 1
  done

  fail_capture "$name"
}

capture_main() {
  local screen="$1"
  local needle="$2"

  adb logcat -c || true
  adb shell am force-stop net.wigle.wigleandroid
  adb shell am start -W \
    -n net.wigle.wigleandroid/.MainActivity \
    --ez ci_visual true \
    --es ci_visual_screen "$screen"

  wait_for_screen "$screen" "net.wigle.wigleandroid/.MainActivity" "$needle"
  sleep 1
  adb exec-out screencap -p > "screenshots/phone-${screen}.png"
  test -s "screenshots/phone-${screen}.png"
}

capture_main list "WiGLE WiFi"
capture_main dash "Dashboard"
capture_main starintel "StarIntel Watchlist"
capture_main map "Map"

adb logcat -c || true
adb shell am force-stop net.wigle.wigleandroid
adb shell am start -W \
  -n net.wigle.wigleandroid/.MacFilterActivity \
  --es net.wigle.wigleandroid.filter.MESSAGE alertFilter \
  --ez ci_visual true

wait_for_screen "watchlist" "net.wigle.wigleandroid/.MacFilterActivity" "MAC / OUI Watchlist"
sleep 1
adb exec-out screencap -p > screenshots/phone-watchlist.png
test -s screenshots/phone-watchlist.png

echo "Phone screenshots:"
ls -lh screenshots/phone-*.png
