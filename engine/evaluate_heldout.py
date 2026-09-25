#!/usr/bin/env python3
"""Held-out test (ALGORITHM.md section 10/11): OPPO apps, evaluated with rules FROZEN at v0.3.0 (see frozen_v0.3.0.sha256).
NEW = packages never seen during rule tuning (independent); DUP = same package also on the vivo dev set (not independent).
T1 trusted-developer cap is OFF, as in evaluate.py.
"""
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import egda  # noqa: E402

D = Path(os.environ.get("EG_DATA", Path(__file__).resolve().parent.parent / "dataset-local")) / "neg_oppo"


def _run(item):
    group, pkg, inst = item
    apks = sorted((D / pkg).glob("*.apk"))
    try:
        app, f, v = egda.assess(apks, installer=inst)
        return group, pkg, (app.labels or [""])[0], v.level, v.score, v.audit, None
    except Exception as e:
        return group, pkg, "", "ERR", 0, "", repr(e)


def main():
    inst = {}
    for line in open(D / "_installers.txt"):
        parts = line.split()
        if parts:
            inst[parts[0]] = parts[1].split("=", 1)[1] if len(parts) > 1 else "null"
    items = [(g, p, inst.get(p)) for g, p in (l.split() for l in open(D / "_split.txt")) if g in ("NEW", "DUP") and (D / p).is_dir()]
    with ProcessPoolExecutor(8) as ex:
        res = sorted(ex.map(_run, items), key=lambda r: (r[0] != "NEW", -r[4]))
    for g, p, lab, lv, sc, audit, err in res:
        print(f"{g} {lv} {sc:2d} {lab[:22]:22s} {p[:44]:44s} {err or ''}")
        if lv in ("C", "D", "N") or err:
            print("      " + audit[:330])
    for g in ("NEW", "DUP"):
        sub = [r for r in res if r[0] == g]
        fp = [r[1] for r in sub if r[3] in ("C", "D")]
        print(f"{g}: false positives (C/D) {len(fp)}/{len(sub)} {fp}  levels={ {lv: sum(r[3] == lv for r in sub) for lv in 'SNCD'} }")
    print("errors:", [r[1] for r in res if r[3] == "ERR"], "| rules", egda.rules()["version"])


if __name__ == "__main__":
    main()
