#!/bin/bash
# Anti-redirect scenarios A-K (testapps redirsim + shop; accessibility service on, debug pref trace on).
# EMULATOR ONLY (run from elder-guard/). Uses $ANDROID_SERIAL, default emulator-5554; refuses any other device, so a real
# phone that is also plugged in is never touched.
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "ABORT: $ANDROID_SERIAL is not an emulator"; exit 1;; esac
ADB_BIN=${ADB_BIN:-adb}   # adb binary (default: adb on PATH), like tools/ui.sh
$ADB_BIN get-state >/dev/null 2>&1 || { echo "ABORT: $ANDROID_SERIAL not connected"; exit 1; }
export ADB_BIN DEV=$ANDROID_SERIAL; ADB=$ADB_BIN
# a previous "loop" run leaves redirsim jumping on start: reset it
$ADB shell am force-stop org.elderguard.testing.redirsim; $ADB shell pm clear org.elderguard.testing.redirsim >/dev/null
r() { $ADB shell am start -n org.elderguard.testing.redirsim/.Main "$@" >/dev/null 2>&1; }
front() { $ADB shell dumpsys activity activities | grep -m1 topResumedActivity | grep -oE '[a-zA-Z0-9_.]+/[A-Za-z0-9_.$]+' ; }
tap() { bash tools/ui.sh tap "$1" >/dev/null || echo "   (not found: $1)"; }
tr() { sleep 1; $ADB logcat -d -s EG-REDIRECT:D | grep -E ' (ALLOW|ASK|BLOCK) ' | sed -E 's/.*win ([^ ]+) front:([^ ]+)->[^ ]+ /   \2 -> \1 /; s/ dwell=.*//; s/org\.elderguard\.testing\.//g; s/com\.google\.android\.apps\.//g'; $ADB logcat -c; }
home() { $ADB shell input keyevent KEYCODE_HOME; sleep 2; $ADB logcat -c; }
play() { r; sleep 2; tap "停止重複跳轉"; sleep 1; }   # user is in the game, tapping (click events observed)
$ADB logcat -c
echo "== A  user taps 'open web page' -> Chrome (expect ALLOW USER_CLICK)"; home; play; tap "使用者點按：開網頁"; sleep 5; echo "   front: $(front)"; tr
echo "== A' Back from Chrome to the game (Chrome not protected -> not judged)"; $ADB shell input keyevent KEYCODE_BACK; sleep 3; echo "   front: $(front)"; tr
echo "== D  no interaction 10 s, then auto jump to shop (expect BLOCK + back to game)"; home; play; sleep 10; r --es run auto_shop; sleep 6; echo "   front: $(front)"; tr
echo "== D2 auto jump to app store (expect BLOCK)"; home; play; sleep 10; r --es run auto_store; sleep 6; echo "   front: $(front)"; tr
echo "== E  full-screen ad, user taps its X (wired to the ad link) (expect ASK/BLOCK)"; home; play; tap "打開全螢幕廣告（按 ✕ 會跳購物）"; sleep 3; $ADB shell input tap 90 390; sleep 4; echo "   front: $(front)"; tr
if front | grep -q RedirectPrompt; then echo "   -> answer 不要"; tap "不要，回到"; sleep 4; echo "   front: $(front)"; tr; fi
echo "== E2 ad page auto-redirects with no tap (expect BLOCK)"; home; play; r --es run ad_auto; sleep 9; echo "   front: $(front)"; tr
echo "== F  shake ad (expect BLOCK, SHAKE_REDIRECT)"; home; play; sleep 6; r --es run shake; sleep 2; $ADB emu sensor set acceleration 0:25:9.8 >/dev/null; sleep 0.3; $ADB emu sensor set acceleration 0:-25:9.8 >/dev/null; sleep 0.3; $ADB emu sensor set acceleration 0:0:9.8 >/dev/null; sleep 5; echo "   front: $(front)"; tr
echo "== I  game -> Home -> user opens shop from launcher (expect ALLOW LAUNCHER_FLOW)"; home; play; $ADB shell input keyevent KEYCODE_HOME; sleep 2; $ADB shell am start -n org.elderguard.testing.shop/.Main >/dev/null; sleep 4; echo "   front: $(front)"; tr
echo "== H  sign-in: user taps login -> browser -> Back (expect ALLOW, ALLOW)"; home; play; tap "登入（開瀏覽器）"; sleep 6; echo "   front: $(front)"; $ADB shell input keyevent KEYCODE_BACK; sleep 3; echo "   after Back: $(front)"; tr
echo "== G  share sheet -> pick a target (expect SYSTEM_FLOW then SHARE_FLOW)"; home; play; tap "分享"; sleep 4; echo "   front: $(front)"; tap "Messages"; sleep 5; echo "   front: $(front)"; tr; $ADB shell input keyevent KEYCODE_BACK; sleep 1; $ADB shell input keyevent KEYCODE_BACK
echo "== K  loop: game re-jumps to shop every time it comes back (expect BLOCK x2 then LOOP -> Home)"; home; play; sleep 6; r --es run loop; sleep 14; echo "   front: $(front)"; tr; r; sleep 1; tap "停止重複跳轉"; home
