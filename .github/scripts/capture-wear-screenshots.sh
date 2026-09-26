#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
WEAR_APK="$(find artifacts -type f -name 'starintelwear-debug.apk' -print -quit)"
if [[ -z "$WEAR_APK" ]]; then
  echo "Wear APK not found. Artifact contents:"
  find artifacts -maxdepth 8 -type f -print
  exit 1
fi

adb install -r "$WEAR_APK"
adb shell pm grant net.wigle.wigleandroid android.permission.POST_NOTIFICATIONS || true
adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.MainActivity \
  --ez ci_visual true
sleep 5

adb exec-out screencap -p > screenshots/wear-dashboard.png
test -s screenshots/wear-dashboard.png

adb shell input swipe 220 330 220 120 350 || true
sleep 2
adb exec-out screencap -p > screenshots/wear-nearby.png
test -s screenshots/wear-nearby.png

adb shell am start -W \
  -n net.wigle.wigleandroid/net.wigle.wigleandroid.starintelwear.ComplicationPreviewActivity
sleep 3
adb exec-out screencap -p > screenshots/wear-complication.png
test -s screenshots/wear-complication.png

echo "Wear screenshots:"
ls -lh screenshots/wear-*.png
