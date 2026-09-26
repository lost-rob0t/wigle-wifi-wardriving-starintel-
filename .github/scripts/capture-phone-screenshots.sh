#!/usr/bin/env bash
set -euo pipefail

mkdir -p screenshots
PHONE_APK="$(find artifacts -type f -name 'wiglewifiwardriving-debug.apk' -print -quit)"
if [[ -z "$PHONE_APK" ]]; then
  echo "Phone APK not found. Artifact contents:"
  find artifacts -maxdepth 8 -type f -print
  exit 1
fi

adb install -r "$PHONE_APK"
adb shell svc wifi enable || true

for permission in \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.POST_NOTIFICATIONS \
  android.permission.BLUETOOTH_SCAN \
  android.permission.BLUETOOTH_CONNECT; do
  adb shell pm grant net.wigle.wigleandroid "$permission" || true
done

capture_main() {
  local screen="$1"
  adb shell am force-stop net.wigle.wigleandroid
  adb shell am start -W \
    -n net.wigle.wigleandroid/.MainActivity \
    --ez ci_visual true \
    --es ci_visual_screen "$screen"
  sleep 5
  adb exec-out screencap -p > "screenshots/phone-${screen}.png"
  test -s "screenshots/phone-${screen}.png"
}

capture_main list
capture_main dash
capture_main starintel
capture_main map

adb shell am force-stop net.wigle.wigleandroid
adb shell am start -W \
  -n net.wigle.wigleandroid/.MacFilterActivity \
  --es net.wigle.wigleandroid.filter.MESSAGE alertFilter \
  --ez ci_visual true
sleep 4
adb exec-out screencap -p > screenshots/phone-watchlist.png
test -s screenshots/phone-watchlist.png

echo "Phone screenshots:"
ls -lh screenshots/phone-*.png
