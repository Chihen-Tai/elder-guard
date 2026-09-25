#!/bin/bash
# ui.sh tap "<text>"   : tap the first node whose text/content-desc contains <text>
# ui.sh texts          : print visible texts
# DEV = device serial (default: the first emulator); ADB_BIN = adb binary (default: adb on PATH)
ADB="${ADB_BIN:-adb} -s ${DEV:-emulator-5554}"
dump() { $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; $ADB shell cat /sdcard/u.xml; }
case "$1" in
 texts) dump | grep -oE 'text="[^"]+"' | sed 's/text=//' ;;
 tap) xy=$(dump | python3 -c "
import sys,re
x=sys.stdin.read(); t=sys.argv[1]
ms=list(re.finditer(r'<node [^>]*?(?:text|content-desc)=\"([^\"]*)\"[^>]*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',x))
hit=next((m for m in ms if m.group(1)==t), None) or next((m for m in ms if t in m.group(1)), None)  # exact match first
if hit:
    a,b,c,d=map(int,hit.groups()[1:]); print((a+c)//2,(b+d)//2)
" "$2"); [ -z "$xy" ] && { echo "NOT FOUND: $2"; exit 1; }; $ADB shell input tap $xy; echo "tapped $2 @ $xy";;
 scroll) $ADB shell input swipe 540 1800 540 700 300;;
esac
