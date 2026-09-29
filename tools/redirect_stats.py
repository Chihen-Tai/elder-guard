#!/usr/bin/env python3
"""Collect and summarise the guard's jump history from a phone during a trial.

The phone keeps redirect_history.jsonl for 7 days only, so every run pulls it (debug build: `run-as`) and merges it into
a local archive (default field-redirect/, git-ignored like every field-* directory: it is real usage data and must
never be committed). The report answers the trial questions without asking the user to test anything by hand:

  - likely wrong blocks / questions: the user tapped "仍然開啟" or "要" afterwards;
  - how long "tap -> other app appears" really takes (the 4 s click window), split by destination kind, with the
    near-misses (>= 3 s) listed;
  - tap-less blocks (a component that does not report taps would show up here).

Reads only the guard's own log (package names, reason codes, times; no URLs, no screen text).

  python3 tools/redirect_stats.py --serial <adb serial>        # pull + merge + report
  python3 tools/redirect_stats.py --no-pull                     # report from the archive only
  python3 tools/redirect_stats.py --anon                        # replace package names (for sharing the report)
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_STORE = os.path.join(HERE, "..", "field-redirect")
SHOP_STORE = {"SHOPPING_REDIRECT", "APP_STORE_REDIRECT"}
WINDOW_MS = 4000  # RedirectEngine.CLICK_WINDOW_MS
NEAR_MS = 3000


def pull(adb, serial):
    cmd = [adb] + (["-s", serial] if serial else []) + ["exec-out", "run-as", "org.elderguard", "cat", "files/redirect_history.jsonl"]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if r.returncode != 0 or "is not debuggable" in r.stdout or "Unknown package" in r.stdout:
        sys.exit(f"pull failed (needs the debug build and exactly one device or --serial): {(r.stderr or r.stdout).strip()[:200]}")
    rows = []
    for line in r.stdout.splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            pass
    return rows


def key(e):
    return f'{e.get("t")}|{e.get("s")}|{e.get("g")}'


def merge(store, fresh):
    os.makedirs(store, exist_ok=True)
    path = os.path.join(store, "history.jsonl")
    old = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                try:
                    e = json.loads(line)
                    old[key(e)] = e
                except ValueError:
                    pass
    before = len(old)
    for e in fresh:
        old[key(e)] = e  # a newer pull carries the outcome (the user's answer) filled in later
    rows = sorted(old.values(), key=lambda e: e.get("t", 0))
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        for e in rows:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    os.replace(tmp, path)
    return rows, len(rows) - before


def pct(sorted_vals, p):
    if not sorted_vals:
        return None
    i = min(len(sorted_vals) - 1, max(0, round(p / 100 * (len(sorted_vals) - 1))))
    return sorted_vals[i]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--adb", default=os.environ.get("ADB_BIN", "adb"))
    ap.add_argument("--store", default=DEFAULT_STORE)
    ap.add_argument("--no-pull", action="store_true")
    ap.add_argument("--anon", action="store_true", help="replace package names with short hashes")
    ap.add_argument("--include-test", action="store_true", help="keep org.elderguard.testing.* test apps")
    a = ap.parse_args()

    if a.no_pull:
        rows, added = merge(a.store, [])
    else:
        rows, added = merge(a.store, pull(a.adb, a.serial))
        # the phone's own long-term counts (kept for the whole trial, counts only)
        cmd = [a.adb] + (["-s", a.serial] if a.serial else []) + ["exec-out", "run-as", "org.elderguard", "cat", "files/redirect_stats.txt"]
        st = subprocess.run(cmd, capture_output=True, text=True, timeout=60).stdout
        if st.startswith("since="):
            kv = dict(l.split("=", 1) for l in st.splitlines() if "=" in l)
            since = time.strftime("%Y-%m-%d", time.localtime(int(kv.get("since", "0")) / 1000))
            print(f"phone stats since {since}: taps shop/store <1/1-2/2-3/3-4 s = {kv.get('tapShop')}, other = {kv.get('tapOther')}, "
                  f"blocks {kv.get('blocks')}, asks {kv.get('asks')}, tap-less {kv.get('tapless')}, answers: "
                  + ", ".join(f"{k[2:]} {v}" for k, v in kv.items() if k.startswith("o.")))
    if not a.include_test:
        rows = [e for e in rows if not str(e.get("s", "")).startswith("org.elderguard.testing.")
                and not str(e.get("g", "")).startswith("org.elderguard.testing.")]

    def name(p):
        return "app-" + hashlib.sha256(p.encode()).hexdigest()[:6] if a.anon and p else p

    def when(e):
        return time.strftime("%m-%d %H:%M", time.localtime(e.get("t", 0) / 1000))

    def line(e):
        ia = e.get("ia", -1)
        return (f'  {when(e)}  {name(e.get("s", ""))} -> {name(e.get("g", ""))}  {e.get("d")} {e.get("sc")}  '
                f'[{e.get("r", "")}]' + (f'  tap {ia / 1000:.1f}s' if ia is not None and ia >= 0 else "")
                + (f'  => {e["o"]}' if e.get("o") else ""))

    print(f"archive: {os.path.normpath(a.store)}  (+{added} new)")
    if not rows:
        print("no entries")
        return
    print(f"entries: {len(rows)}  from {when(rows[0])} to {when(rows[-1])}")
    counts = {}
    for e in rows:
        counts[e.get("d")] = counts.get(e.get("d"), 0) + 1
    print("decisions: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())))

    print("\n1) likely wrong: blocked / asked, but the user opened it anyway")
    wrong = [e for e in rows if e.get("o") in ("user_open_anyway", "user_allow", "user_allow_always")]
    print("\n".join(line(e) for e in wrong) if wrong else "  none")

    print(f"\n2) tap -> other app, for allowed taps (click window {WINDOW_MS / 1000:.0f} s)")
    taps = [e for e in rows if e.get("d") == "ALLOW" and "USER_CLICK" in e.get("r", "") and (e.get("ia") or -1) >= 0]
    for label, sel in (("shop / store", lambda e: bool(SHOP_STORE & set(e.get("r", "").split(",")))),
                       ("other", lambda e: not SHOP_STORE & set(e.get("r", "").split(",")))):
        v = sorted(e["ia"] for e in taps if sel(e))
        if not v:
            print(f"  {label:12s} n=0")
            continue
        buckets = [sum(1 for x in v if lo <= x < hi) for lo, hi in ((0, 1000), (1000, 2000), (2000, 3000), (3000, 4001))]
        print(f"  {label:12s} n={len(v)}  median {pct(v, 50) / 1000:.1f}s  p90 {pct(v, 90) / 1000:.1f}s  max {v[-1] / 1000:.1f}s"
              f"  | <1s {buckets[0]}  1-2s {buckets[1]}  2-3s {buckets[2]}  3-4s {buckets[3]}")
    near = [e for e in taps if e["ia"] >= NEAR_MS]
    print(f"  near the limit (>= {NEAR_MS / 1000:.0f} s):")
    print("\n".join(line(e) for e in near) if near else "    none")

    print("\n3) blocked / asked with no tap seen (a missed tap would show up here)")
    tapless = [e for e in rows if e.get("d") in ("BLOCK", "ASK") and "NO_USER_INTERACTION" in e.get("r", "")]
    print("\n".join(line(e) for e in tapless[-20:]) if tapless else "  none")

    print("\n4) all blocks / questions (latest 20)")
    acted = [e for e in rows if e.get("d") in ("BLOCK", "ASK")]
    print("\n".join(line(e) for e in acted[-20:]) if acted else "  none")


if __name__ == "__main__":
    main()
