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
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put global stay_on_while_plugged_in 0 || true
adb shell settings put secure doze_enabled 0 || true
# The Wear emulator can foreground its charging experience on CI and hide the app
# behind a black "Starting..." screen. Disable that emulator-only system surface.
adb shell pm disable-user --user 0 \
  com.google.android.wearable.sysui/com.google.android.clockwork.sysui.experiences.charging.ChargingActivity || true
adb shell am force-stop com.google.android.wearable.sysui || true
adb shell input keyevent KEYCODE_WAKEUP || true
sleep 3

adb install -r "$WEAR_APK"
adb shell pm grant net.wigle.wigleandroid android.permission.POST_NOTIFICATIONS || true

focused_window() {
  adb shell dumpsys window windows 2>/dev/null \
    | grep -m1 -E 'mCurrentFocus|mFocusedApp' || true
}

resumed_activity() {
  adb shell dumpsys activity activities 2>/dev/null \
    | grep -m1 -E 'mResumedActivity|topResumedActivity|ResumedActivity' || true
}

starting_window() {
  adb shell dumpsys window windows 2>/dev/null \
    | grep -m1 -E 'Starting.*net\.wigle\.wigleandroid|Splash Screen.*net\.wigle\.wigleandroid' || true
}

fail_capture() {
  local name="$1"
  echo "::error::Visual CI did not render expected Wear OS screen: $name"
  echo "Resumed activity: $(resumed_activity)"
  echo "Focused window: $(focused_window)"
  echo "Starting window: $(starting_window)"
  adb shell dumpsys activity activities > "diagnostics/wear-${name}-activity.txt" || true
  adb shell dumpsys window windows > "diagnostics/wear-${name}-window.txt" || true
  adb logcat -d -v threadtime > "diagnostics/wear-${name}-logcat.txt" || true
  adb exec-out screencap -p > "screenshots/FAILED-wear-${name}.png" || true
  echo "----- Wear runtime crash excerpt -----"
  grep -E -A35 -B8 'FATAL EXCEPTION|AndroidRuntime|Process: net\.wigle|Caused by:|am_crash|Force finishing' \
    "diagnostics/wear-${name}-logcat.txt" | tail -n 260 || true
  echo "----- Wear process / task state -----"
  grep -E 'net\.wigle\.wigleandroid|mResumedActivity|topResumedActivity|mCurrentFocus|mFocusedApp|Starting' \
    "diagnostics/wear-${name}-activity.txt" "diagnostics/wear-${name}-window.txt" | tail -n 160 || true
  exit 1
}

wait_for_wear_component() {
  local name="$1"
  local component="$2"
  local i resumed starting
  for i in $(seq 1 50); do
    resumed="$(resumed_activity)"
    starting="$(starting_window)"
    if [[ "$resumed" == *"$component"* ]] && [[ -z "$starting" ]]; then
      return 0
    fi
    # If SystemUI resurrects the charging experience, dismiss it and bring the app back.
    if [[ "$resumed" == *"ChargingActivity"* ]]; then
      adb shell input keyevent KEYCODE_BACK || true
      adb shell input keyevent KEYCODE_WAKEUP || true
    fi
    sleep 0.5
  done
  fail_capture "$name"
}

wait_for_wear_ui() {
  local name="$1"
  local component="$2"
  local needle="$3"
  local i resumed xml

  for i in $(seq 1 5); do
    sleep 2
    resumed="$(resumed_activity)"
    xml=""
    if timeout 8s adb shell uiautomator dump /sdcard/starintel-window.xml >/dev/null 2>&1; then
      xml="$(adb shell cat /sdcard/starintel-window.xml 2>/dev/null || true)"
    fi
    if [[ "$resumed" == *"$component"* ]] \
      && [[ "$xml" == *"$needle"* ]] \
      && [[ "$xml" != *"Starting..."* ]]; then
      return 0
    fi
  done

  echo "$xml" > "diagnostics/wear-${name}-ui.xml" || true
  fail_capture "$name"
}

adb logcat -c || true
adb shell am force-stop net.wigle.wigleandroid
adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.MainActivity \
  --ez ci_visual true

wait_for_wear_component "dashboard" "net.wigle.wigleandroid/.starintelwear.MainActivity"
wait_for_wear_ui "dashboard" "net.wigle.wigleandroid/.starintelwear.MainActivity" "STARINTEL"
adb exec-out screencap -p > screenshots/wear-dashboard.png
test -s screenshots/wear-dashboard.png

adb shell input swipe 220 330 220 120 350 || true
wait_for_wear_component "nearby" "net.wigle.wigleandroid/.starintelwear.MainActivity"
wait_for_wear_ui "nearby" "net.wigle.wigleandroid/.starintelwear.MainActivity" "NEARBY"
adb exec-out screencap -p > screenshots/wear-nearby.png
test -s screenshots/wear-nearby.png

adb logcat -c || true
adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.ComplicationPreviewActivity
wait_for_wear_component "complication" "net.wigle.wigleandroid/.starintelwear.ComplicationPreviewActivity"
wait_for_wear_ui "complication" "net.wigle.wigleandroid/.starintelwear.ComplicationPreviewActivity" "StarIntel complication preview"
adb exec-out screencap -p > screenshots/wear-complication.png
test -s screenshots/wear-complication.png

echo "Wear screenshots:"
ls -lh screenshots/wear-*.png
