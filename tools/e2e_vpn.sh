#!/bin/bash
# Ad-protection (VPN) scenarios: rules, network switches, airplane mode, DoH, per-app (toggles Wi-Fi / airplane mode!).
# EMULATOR ONLY (run from elder-guard/). Uses $ANDROID_SERIAL, default emulator-5554; refuses any other device, so a real
# phone that is also plugged in is never touched.
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo "ABORT: $ANDROID_SERIAL is not an emulator"; exit 1;; esac
ADB_BIN=${ADB_BIN:-adb}   # adb binary (default: adb on PATH), like tools/ui.sh
$ADB_BIN get-state >/dev/null 2>&1 || { echo "ABORT: $ANDROID_SERIAL not connected"; exit 1; }
ADB=$ADB_BIN
h() { $ADB shell am start -n org.elderguard/.debug.DebugHarnessActivity --es cmd "$@" >/dev/null 2>&1; sleep ${W:-3}; $ADB logcat -d -s EG-HARNESS:I | tail -1 | cut -c47-; }
res() { $ADB logcat -c; $ADB shell am start -n org.elderguard.testing.redirsim/.Main --es run resolve --es names "$1" >/dev/null 2>&1; sleep ${2:-4}; $ADB logcat -d -s EG-BENCH:I | grep resolve | sed -E 's/.*resolve //; s/\[([^,\]]*)(,[^]]*)?\]/\1/' | tr '\n' ';'; echo; }
under() { $ADB shell dumpsys connectivity | grep -oE 'VPN:org.elderguard.*UnderlyingNetworks: \[[0-9]*\]' | grep -oE 'Underlying.*'; }
N="doubleclick.net,example.com"
echo "== baseline";                     res $N; echo "   $(under)"; W=2 h vpn_status
echo "== custom block example.com";     h block --es arg example.com; res $N
echo "== custom allow doubleclick.net"; h allow --es arg doubleclick.net; res $N
h unrule --es arg example.com >/dev/null; h unrule --es arg doubleclick.net >/dev/null
echo "== Wi-Fi off (mobile data)";       $ADB shell svc wifi disable; sleep 8; res $N 6; echo "   $(under)"
echo "== Wi-Fi back on";                 $ADB shell svc wifi enable; sleep 12; res $N 6; echo "   $(under)"
echo "== airplane mode on";              $ADB shell cmd connectivity airplane-mode enable; sleep 6; res "example.com" 8; W=2 h vpn_status
echo "== airplane mode off";             $ADB shell cmd connectivity airplane-mode disable; sleep 15; res $N 6; W=2 h vpn_status
echo "== VPN stop -> start";             h vpn_stop; res $N; h vpn_start; sleep 3; res $N
echo "== DoH (Cloudflare)";              h upstream_set --es arg DOH_CLOUDFLARE; sleep 3; res "$N,www.wikipedia.org" 6; W=2 h vpn_status
echo "== UDP Quad9";                     h upstream_set --es arg QUAD9; sleep 3; res $N 6
echo "== back to AUTO";                  h upstream_set --es arg AUTO; sleep 3; res $N
echo "== per-app bypass redirsim";       h bypass --es arg org.elderguard.testing.redirsim; sleep 3; res $N
h unbypass --es arg org.elderguard.testing.redirsim; sleep 3; echo "== bypass removed"; res $N
