#!/bin/bash
# Screenshot only when our app is the focused window and the keyguard is not showing; crop the status bar.
# Usage: safe_shot.sh <name>
# Uses ANDROID_SERIAL to pick the device. Shots are private (design/shots/ is git-ignored).
ADB=${ADB_BIN:-adb}; D="$(cd "$(dirname "$0")/.." && pwd)/design/shots"; mkdir -p "$D"
W=$($ADB shell dumpsys window)
focus=$(echo "$W" | grep -m1 mCurrentFocus)
if ! echo "$focus" | grep -q "org.elderguard/"; then echo "ABORT: focus is not our app ($focus)"; exit 1; fi
if echo "$W" | grep -q "isKeyguardShowing=true"; then echo "ABORT: keyguard showing"; exit 1; fi
$ADB exec-out screencap -p > $D/.raw.png
convert $D/.raw.png -gravity North -chop 0x110 -resize 36% $D/$1.jpg && rm -f $D/.raw.png && echo "saved $D/$1.jpg"
