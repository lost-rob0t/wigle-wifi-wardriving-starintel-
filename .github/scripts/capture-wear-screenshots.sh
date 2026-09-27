#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots diagnostics
WEAR_APK="$(find artifacts -type f -name 'starintelwear-debug.apk' -print -quit)"
if [[ -z "$WEAR_APK" ]]; then
  echo "Wear APK not found. Artifact contents:"
  find artifacts -maxdepth 8 -type f -print
  exit 1
fi

adb wait-for-device
for i in $(seq 1 60); do
  [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]] && break
  sleep 1
done
adb shell input keyevent 82 || true
sleep 6

adb install -r "$WEAR_APK"
adb shell pm grant net.wigle.wigleandroid android.permission.POST_NOTIFICATIONS || true

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
  echo "::error::Visual CI did not render expected Wear OS screen: $name"
  echo "Resumed activity: $(resumed_activity)"
  dump_ui > "diagnostics/wear-${name}-ui.xml" || true
  adb logcat -d -v threadtime > "diagnostics/wear-${name}-logcat.txt" || true
  adb shell dumpsys activity activities > "diagnostics/wear-${name}-activity.txt" || true
  adb shell dumpsys window windows > "diagnostics/wear-${name}-window.txt" || true
  adb exec-out screencap -p > "screenshots/FAILED-wear-${name}.png" || true
  echo "----- Wear runtime crash excerpt -----"
  grep -E -A35 -B8 'FATAL EXCEPTION|AndroidRuntime|Process: net\.wigle|Caused by:|am_crash|Force finishing' \
    "diagnostics/wear-${name}-logcat.txt" | tail -n 260 || true
  echo "----- Wear process / task state -----"
  grep -E 'net\.wigle\.wigleandroid|mResumedActivity|topResumedActivity|mCurrentFocus|mFocusedApp' \
    "diagnostics/wear-${name}-activity.txt" "diagnostics/wear-${name}-window.txt" | tail -n 120 || true
  exit 1
}

wait_for_wear_screen() {
  local name="$1"
  local component="$2"
  local needle="$3"
  local i current ui

  for i in $(seq 1 40); do
    current="$(resumed_activity)"
    ui="$(dump_ui)"
    if [[ "$current" == *"$component"* ]] \
      && [[ "$ui" == *"$needle"* ]] \
      && [[ "$ui" != *"Starting..."* ]]; then
      return 0
    fi
    sleep 1
  done

  fail_capture "$name"
}

adb logcat -c || true
adb shell am force-stop net.wigle.wigleandroid
adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.MainActivity \
  --ez ci_visual true

wait_for_wear_screen "dashboard" "net.wigle.wigleandroid/.starintelwear.MainActivity" "STARINTEL"
sleep 1
adb exec-out screencap -p > screenshots/wear-dashboard.png
test -s screenshots/wear-dashboard.png

adb shell input swipe 220 330 220 120 350 || true
sleep 1
wait_for_wear_screen "nearby" "net.wigle.wigleandroid/.starintelwear.MainActivity" "NEARBY"
adb exec-out screencap -p > screenshots/wear-nearby.png
test -s screenshots/wear-nearby.png

adb logcat -c || true
adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.ComplicationPreviewActivity
wait_for_wear_screen "complication" "net.wigle.wigleandroid/.starintelwear.ComplicationPreviewActivity" "Complication"
sleep 1
adb exec-out screencap -p > screenshots/wear-complication.png
test -s screenshots/wear-complication.png

echo "Wear screenshots:"
ls -lh screenshots/wear-*.png
