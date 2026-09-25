#!/usr/bin/env python3
"""Evaluate EGDA on the local dataset (ALGORITHM.md section 10). APKs never leave this machine and are not in the repo.

Labels are our own judgement from the 2026-09-25 phone clean-ups, not external ground truth.
T1 (trusted-developer cap) is deliberately OFF here: whitelisting the negatives' certificates would make the
false-positive test meaningless.
"""
import csv
import os
import sys
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import egda  # noqa: E402

# Local data, never committed (see .gitignore). Layout under $EG_DATA (default: <repo>/dataset-local):
#   pos_phone_a/, pos_phone_b/   APK backups of apps removed from test phones A and B (<pkg>/*.apk under full/)
#   neg_vivo/<pkg>/*.apk         normal apps from phone B (+ _installers.txt)
#   neg_extra/<pkg>/*.apk        any further normal apps
#   installed_a.csv, installed_b.csv   package,installer inventories
DATA = Path(os.environ.get("EG_DATA", Path(__file__).resolve().parent.parent / "dataset-local"))
P1 = DATA / "pos_phone_a"
P2 = DATA / "pos_phone_b"
NEG = DATA / "neg_vivo"
NEG_EXTRA = DATA / "neg_extra"

POS_ADW_P1 = ["com.scannerreader.qrcode.creatorfree", "qrcodereader.barcodescanner.scan.qrscanner",
              "com.gps.map.navigation.tracker.location.compass.handy", "map.ly.gps.navigation.route.planer"]
POS_ADW_P2 = ["com.convenient.secureclean", "com.files.restore.recovery.tool.deleted.document.photo.video.audio.app.mobile.scan.utility",
              "com.photo.recovery.file.video.image", "com.rephoto.recover.pro", "com.myhotelaffiliate.recovery",
              "com.ezt.pdfreader.pdfviewer", "com.paper.doc.reader", "com.pdfedit.qunatumai", "com.qunatumai.doc.reader",
              "word.office.docxviewer.document.docx.reader", "gallery.photogallery.pictures.vault.album"]
OTHER_P2 = {"com.scaleup.chatai": "sub-trap?", "com.block.juggle": "game", "com.oakever.tiletrip": "game",
            "com.heytap.browser": "oem-app", "com.heytap.music": "oem-app", "com.coloros.video": "oem-app", "com.oplus.games": "oem-app"}
GREY = {"com.gamma.scan"}


def installers() -> dict:
    m = {}
    for f in (DATA / "installed_a.csv", DATA / "installed_b.csv"):
        for r in csv.DictReader(open(f, encoding="utf-8-sig")):
            m[r["package"]] = r["installer"]
    for line in open(NEG / "_installers.txt"):
        parts = line.split()
        if parts:
            m[parts[0]] = parts[1].split("=", 1)[1] if len(parts) > 1 else "null"
    return m


def dataset():
    items = []  # (label, package, [apk paths])
    for p in POS_ADW_P1:
        items.append(("POS_ADW", p, sorted((P1 / "full" / p).glob("*.apk"))))
    for p in POS_ADW_P2:
        items.append(("POS_ADW", p, sorted((P2 / "full" / p).glob("*.apk"))))
    items.append(("POS_FIN", "com.tgcpro.apps", [P1 / "com.tgcpro.apps.apk"] + sorted(P1.glob("tgc_split_*.apk"))))
    items.append(("POS_FIN", "com.finstar.chat", [P1 / "com.finstar.chat.apk"]))
    for p, why in OTHER_P2.items():
        items.append((f"OTHER:{why}", p, sorted((P2 / "full" / p).glob("*.apk"))))
    if NEG_EXTRA.is_dir():
        for d in sorted(NEG_EXTRA.iterdir()):
            if d.is_dir():
                items.append(("NEG", d.name, sorted(d.glob("*.apk"))))
    for d in sorted(NEG.iterdir()):
        if d.is_dir():
            items.append(("GREY" if d.name in GREY else "NEG", d.name, sorted(d.glob("*.apk"))))
    return items


def _run(item):
    label, pkg, apks, inst = item
    try:
        # The base APK may be named <pkg>.apk in phone-1 backups; egda picks the shortest name as base.
        app, f, v = egda.assess(apks, installer=inst)
        return label, pkg, app.labels[:1], v.level, v.score, v.reasons, v.audit, None
    except Exception as e:  # report, never hide
        return label, pkg, [], "ERR", 0, [], "", repr(e)


def main():
    inst = installers()
    items = [(l, p, a, inst.get(p)) for l, p, a in dataset() if a]
    with ProcessPoolExecutor(8) as ex:
        results = list(ex.map(_run, items))
    order = {"POS_FIN": 0, "POS_ADW": 1, "GREY": 2, "NEG": 4}
    results.sort(key=lambda r: (order.get(r[0], 3), r[0], -r[4]))
    for label, pkg, lab, level, score, reasons, audit, err in results:
        name = (lab[0] if lab else "")[:22]
        print(f"{label:16s} {level} {score:2d}  {name:22s} {pkg[:44]:44s} {err or ''}")
        if level in ("C", "D") or label.startswith("POS") or err:
            print(f"{'':20s}{audit}")
    pos_adw = [r for r in results if r[0] == "POS_ADW"]
    pos_fin = [r for r in results if r[0] == "POS_FIN"]
    neg = [r for r in results if r[0] == "NEG"]
    errs = [r for r in results if r[3] == "ERR"]
    hit = sum(r[3] in ("C", "D") for r in pos_adw)
    fp = [r for r in neg if r[3] in ("C", "D")]
    print("\n==== summary (rules", egda.rules()["version"], ", T1 off) ====")
    print(f"POS_ADW recall (C/D): {hit}/{len(pos_adw)} = {hit / max(1, len(pos_adw)):.0%}   gate >= 90%")
    print(f"POS_FIN as D: {sum(r[3] == 'D' for r in pos_fin)}/{len(pos_fin)}   gate = 100%")
    print(f"NEG false positives (C/D): {len(fp)}/{len(neg)}   gate = 0   {[r[1] for r in fp]}")
    print(f"NEG level counts: " + str({lv: sum(r[3] == lv for r in neg) for lv in 'SNCD'}))
    print(f"errors: {len(errs)} {[r[1] for r in errs]}")


if __name__ == "__main__":
    main()
