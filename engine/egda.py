#!/usr/bin/env python3
"""EGDA reference implementation (desktop, offline). Spec: ../docs/ALGORITHM.md

An "app" is a directory (or list) of APK files: base.apk plus optional split_*.apk.
Manifest is read with aapt2 (official Android build tool); dex string tables are parsed directly.
"""
from __future__ import annotations

import os
import shutil
import json
import re
import struct
import subprocess
import zipfile
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

# Official Android SDK tools; set AAPT2 / APKSIGNER or put them on PATH.
AAPT2 = os.environ.get("AAPT2") or shutil.which("aapt2") or "aapt2"
APKSIGNER = os.environ.get("APKSIGNER") or shutil.which("apksigner") or "apksigner"
RULES = Path(__file__).with_name("rules.json")
READ_BUDGET = 96 * 1024 * 1024  # bytes of dex + native + assets read per app (spec 4.1)

ANDROID_NS = "http://schemas.android.com/apk/res/android:"


# ---------------------------------------------------------------- manifest (aapt2 xmltree)
@dataclass
class Node:
    tag: str
    attrs: dict = field(default_factory=dict)
    children: list = field(default_factory=list)

    def iter(self, tag):
        for c in self.children:
            if c.tag == tag:
                yield c
            yield from c.iter(tag)


_ATTR = re.compile(r'^A: (?:' + re.escape(ANDROID_NS) + r')?([\w:]+)(?:\(0x[0-9a-f]+\))?=(.*)$')


def _attr_value(raw: str):
    raw = raw.strip()
    if raw.startswith('"'):
        v = raw[1:raw.index('"', 1)]
        return {"true": True, "false": False}.get(v, v)
    if raw in ("true", "false"):
        return raw == "true"
    m = re.match(r'\(type 0x12\)0x([0-9a-f]+)', raw)  # boolean
    if m:
        return int(m.group(1), 16) != 0
    m = re.match(r'\(type 0x1[06]\)0x([0-9a-f]+)', raw)  # int / hex int
    if m:
        return int(m.group(1), 16)
    return raw


def read_manifest(base_apk: Path) -> Node:
    out = subprocess.run([AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", str(base_apk)],
                         capture_output=True, text=True, errors="replace").stdout
    root = Node("#root")
    stack = [(-1, root)]
    for line in out.splitlines():
        stripped = line.lstrip(" ")
        depth = (len(line) - len(stripped)) // 2
        if stripped.startswith("E: "):
            node = Node(stripped[3:].split(" ")[0])
            while stack[-1][0] >= depth:
                stack.pop()
            stack[-1][1].children.append(node)
            stack.append((depth, node))
        elif stripped.startswith("A: "):
            m = _ATTR.match(stripped)
            if m:
                while stack[-1][0] >= depth:
                    stack.pop()
                stack[-1][1].attrs[m.group(1)] = _attr_value(m.group(2))
    return root


def read_labels(base_apk: Path) -> list[str]:
    out = subprocess.run([AAPT2, "dump", "badging", str(base_apk)], capture_output=True, text=True, errors="replace").stdout
    labels = []
    for line in out.splitlines():
        if line.startswith("application-label") or line.startswith("application: label="):
            m = re.search(r"label(?:-[\w-]+)?[:=]'([^']*)'", line)
            if m and m.group(1) and m.group(1) not in labels:
                labels.append(m.group(1))
    return labels


def read_res_strings(base_apk: Path) -> list[str]:
    out = subprocess.run([AAPT2, "dump", "strings", str(base_apk)], capture_output=True, text=True, errors="replace").stdout
    return [l.split(" : ", 1)[1] for l in out.splitlines() if l.startswith("String #") and " : " in l]


def signer_sha256(base_apk: Path) -> str | None:
    out = subprocess.run([APKSIGNER, "verify", "--print-certs", str(base_apk)], capture_output=True, text=True, errors="replace").stdout
    m = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", out)
    return m.group(1) if m else None


# ---------------------------------------------------------------- dex string table (spec 4.1)
def _uleb128(b: bytes, off: int):
    result = shift = 0
    while True:
        byte = b[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, off
        shift += 7


def dex_strings(data: bytes) -> list[str]:
    if data[:4] != b"dex\n":
        return []
    size, off = struct.unpack_from("<II", data, 0x38)
    out = []
    for i in range(size):
        (p,) = struct.unpack_from("<I", data, off + 4 * i)
        _, p = _uleb128(data, p)
        end = data.index(b"\0", p)
        out.append(data[p:end].decode("utf-8", "replace"))  # MUTF-8 ~ UTF-8 for our purposes
    return out


_PRINTABLE = re.compile(rb"[\x20-\x7e]{6,}|(?:[\xe4-\xe9][\x80-\xbf]{2}){2,}")


# ---------------------------------------------------------------- extraction
@dataclass
class App:
    package: str
    labels: list
    manifest: Node
    dex: set               # all dex strings (type descriptors + literals)
    text: str              # lower-cased corpus: dex literals + resource strings + native/asset strings
    cert: str | None
    partial: bool
    installer: str | None = None
    lib_names: list = field(default_factory=list)
    ui_text: str = ""


def extract(apks: list[Path], installer: str | None = None) -> App:
    base = next((a for a in apks if a.name == "base.apk"), None) or min(apks, key=lambda a: len(a.name))
    man = read_manifest(base)
    pkg = next(man.iter("manifest")).attrs.get("package", base.stem)
    budget, partial = READ_BUDGET, False
    dex, chunks, ui_chunks = set(), [], []
    lib_names = []
    entries = []  # (priority, apk, info): dex first, then scripts, then native libs (spec 4.1 / 6.4)
    for apk in apks:
        try:
            with zipfile.ZipFile(apk) as z:
                for info in z.infolist():
                    n = info.filename
                    if n.startswith("classes") and n.endswith(".dex"):
                        entries.append((0, apk, info))
                    elif n.startswith("assets/") and (n.endswith((".js", ".bundle", ".json", ".html")) or n.endswith("global-metadata.dat")):
                        entries.append((1, apk, info))
                    elif n.startswith("lib/") and n.endswith(".so"):
                        lib_names.append(n.rsplit("/", 1)[-1])
                        entries.append((2, apk, info))
        except zipfile.BadZipFile:
            partial = True
    entries.sort(key=lambda e: (e[0], e[2].file_size))
    for prio, apk, info in entries:
        if info.file_size > budget:
            if prio == 0:
                partial = True  # only missing dex makes the scan partial; native/script text is best-effort
            continue
        with zipfile.ZipFile(apk) as z:
            data = z.read(info.filename)
        budget -= len(data)
        if prio == 0:
            dex.update(dex_strings(data))
        else:
            found = [m.decode("utf-8", "replace") for m in _PRINTABLE.findall(data)]
            chunks.extend(found)
            # UI text lives in resources or in framework bundles (Flutter libapp.so, RN bundle, Unity metadata, web assets);
            # other native libraries mostly hold logs/identifiers and are excluded from UI-text rules (spec v0.4).
            if prio == 1 or info.filename.endswith("/libapp.so"):
                ui_chunks.extend(found)
    res = read_res_strings(base)
    literals = [s for s in dex if not (s.startswith("L") and s.endswith(";"))]
    text = "\n".join(literals + res + chunks).lower()
    ui_text = "\n".join(res + ui_chunks).lower()
    return App(pkg, read_labels(base), man, dex, text, signer_sha256(base), partial, installer, lib_names, ui_text)


# ---------------------------------------------------------------- rules
@lru_cache
def rules() -> dict:
    return json.loads(RULES.read_text(encoding="utf-8"))


@lru_cache
def _dictionary() -> frozenset:
    words = {w.strip().lower() for w in open("/usr/share/dict/words", encoding="utf-8", errors="ignore")
             if w.strip().isalpha() and len(w.strip()) >= 3}
    return frozenset(words | set(rules()["obfuscation"]["extra_words"]))


def _segmentable(tok: str, vocab: frozenset, syllables: frozenset) -> bool:
    """True if tok can be split entirely into dictionary words (len>=3) or pinyin syllables."""
    n = len(tok)
    ok = [False] * (n + 1)
    ok[0] = True
    for i in range(n):
        if not ok[i]:
            continue
        for j in range(i + 1, n + 1):
            piece = tok[i:j]
            if (len(piece) >= 3 and piece in vocab) or piece in syllables:
                ok[j] = True
    return ok[n]


def _kw_regex(words: list[str]) -> re.Pattern:
    parts = []
    for w in words:
        w = w.lower()
        if re.fullmatch(r"[a-z0-9 ]+", w):
            parts.append(r"(?<![a-z0-9_.])" + re.escape(w) + r"(?![a-z0-9_])")  # Latin word boundary; '_' '.' digits count as identifier chars
        else:
            parts.append(re.escape(w))
    return re.compile("|".join(parts))


# ---------------------------------------------------------------- features (spec 4.2)
@dataclass
class Features:
    src: str
    ctrl: list
    snoop: list
    ad_networks: list
    mediation: bool
    ooa: bool
    ooa_named: list
    triggers: list
    obf: bool
    obf_examples: list
    packed: bool
    bait: str | None
    fin_groups: list
    im_wallet: bool
    im_secret: bool
    weekly_sub: bool
    game: bool
    impersonates: str | None


def _components(app: App):
    appnode = next(app.manifest.iter("application"), Node("application"))
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in appnode.iter(tag):
            name = str(c.attrs.get("name", ""))
            if name.startswith("."):
                name = app.package + name
            yield tag, name, c


def _in_app_namespace(name: str, pkg: str) -> bool:
    root = ".".join(pkg.split(".")[:2])
    return name.startswith(root + ".")


def features(app: App) -> Features:
    R = rules()
    perms = {str(p.attrs.get("name", "")) for p in app.manifest.iter("uses-permission")}
    comps = list(_components(app))

    # F-SRC
    inst = app.installer or ""
    if inst == "com.android.vending":
        src = "store_play"
    elif inst in R["oem_stores"]:
        src = "store_oem"
    elif inst in R["system_installers"] or inst in ("", "null"):
        src = "sideload"
    else:
        src = "app_installed"

    # F-PERM
    bound = {str(c.attrs.get("permission", "")) for _, _, c in comps}
    ctrl = sorted({p.split(".")[-1] for p in (perms | bound) if p in R["perm_ctrl"]})
    snoop = sorted({p.split(".")[-1] for p in perms if p in R["perm_snoop"]})

    # F-SDK
    ad = sorted({net for net, prefixes in R["ad_sdks"].items() if any(any(s.startswith(p) for p in prefixes) for s in _descriptor_prefix_index(app))})
    mediation = any(net in ad for net in R["mediation"])

    # F-TRIG
    actions = set()
    for tag, _, c in comps:
        if tag == "receiver":
            for f in c.iter("intent-filter"):
                for a in f.iter("action"):
                    actions.add(str(a.attrs.get("name", "")))
    triggers = sorted(a.split(".")[-1] for a in actions if a in R["trigger_actions"])
    has_fgs = "android.permission.FOREGROUND_SERVICE" in perms
    n_trig = len(triggers) if has_fgs else 0
    has_nls = "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" in bound

    # F-OBF (spec 4.2): a component is "nonsense" when its class name and package path contain
    # >= N tokens that are neither dictionary words nor pinyin. One brand token (e.g. a company abbreviation) is not enough.
    vocab, syl = _dictionary(), frozenset(R["obfuscation"]["pinyin"])
    suffixes = tuple(R["obfuscation"]["suffixes"])
    lib_prefixes = tuple(R["sdk_component_prefixes"] + R["lib_component_prefixes"])
    own = [n for _, n, _ in comps if not n.startswith(lib_prefixes)]
    need = R["thresholds"]["nonsense_tokens_per_component"]

    def nonsense(n: str) -> bool:
        *pkg_segs, simple = n.split(".")
        for suf in suffixes:
            if simple.endswith(suf) and len(simple) > len(suf):
                simple = simple[: -len(suf)]
                break
        toks = re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])", simple) + [p for p in pkg_segs[1:] if p.isalpha()]
        return sum(len(t) >= 5 and not _segmentable(t.lower(), vocab, syl) for t in toks) >= need

    weird = [n for n in own if nonsense(n)]
    obf = len(own) >= 4 and len(weird) / len(own) >= R["obfuscation"]["ratio"]

    # F-OOA (spec 4.2): pop-up style activity (excludeFromRecents + foreign taskAffinity) living in a nonsense
    # namespace, plus a trigger. Legit apps use the same flags for notification/share trampolines with real names.
    popup_like = [name for tag, name, c in comps
                  if tag == "activity" and not name.startswith(lib_prefixes)
                  and c.attrs.get("excludeFromRecents") is True
                  and c.attrs.get("taskAffinity") is not None and c.attrs.get("taskAffinity") != app.package
                  and nonsense(name)]
    ooa_named = [n for t, n, _ in comps if t == "activity" and not n.startswith(lib_prefixes)
                 and re.search(R["ooa_name_regex"], n.rsplit(".", 1)[-1], re.I)]
    ooa = bool(popup_like) and (n_trig >= 1 or has_nls)

    # F-PACK
    packed = any(any(s.startswith(p) for p in R["packers"]["dex"]) for s in _descriptor_prefix_index(app)) \
        or any(any(l.startswith(p) for p in R["packers"]["libs"]) for l in app.lib_names)

    # F-CAT
    # Package dots/underscores are separators here, unlike in code text (v0.4 regression: "map.ly.gps" stopped matching "gps").
    # v0.4.2: split joined labels ("PDFReader" -> "PDF Reader"), as the phone does (StaticRisk.splitCamel)
    split = [re.sub(r"(?<=[A-Z])(?=[A-Z][a-z])", " ", re.sub(r"(?<=[a-z0-9])(?=[A-Z])", " ", l)) for l in app.labels]
    name_text = " ".join(split + [re.sub(r"[._]", " ", app.package)]).lower()
    bait = next((cat for cat, kws in R["bait_categories"].items() if _kw_regex(kws).search(name_text)), None)

    # F-FIN
    fin = [g for g, kws in R["fin_groups"].items() if _kw_regex(kws).search(app.ui_text)]

    # F-IM
    im_sdk = any(any(s.startswith(p) for p in R["im_sdks"]) for s in _descriptor_prefix_index(app))
    chat = im_sdk or len(_kw_regex(R["im_chat_words"]).findall(app.ui_text)) >= 3
    wallet = bool(_kw_regex(R["im_wallet_words"]).search(app.ui_text))
    secret = bool(_kw_regex(R["im_secret_words"]).search(app.ui_text))

    # F-SUBS
    billing = any(s.startswith("Lcom/android/billingclient/") for s in _descriptor_prefix_index(app))
    weekly = billing and bool(_kw_regex(R["weekly_words"]).search(app.ui_text)) and (bait is not None or bool(_kw_regex(R["ai_words"]).search(name_text)))

    # game (T2)
    appnode = next(app.manifest.iter("application"), Node("application"))
    game = appnode.attrs.get("appCategory") == 0 or appnode.attrs.get("isGame") is True \
        or any(any(s.startswith(p) for p in R["game_engines"]["dex"]) for s in _descriptor_prefix_index(app)) \
        or any(l in R["game_engines"]["libs"] for l in app.lib_names)

    # F-IMP
    imp = None
    norm = [re.sub(r"[\s\W_]+", "", l.lower()) for l in app.labels]
    for brand, spec in R["protected_brands"].items():
        if any(_similar(n, re.sub(r"[\s\W_]+", "", a.lower())) for n in norm for a in spec["names"]) and app.cert not in spec["certs"]:
            imp = brand
            break

    return Features(src, ctrl, snoop, ad, mediation, ooa, ooa_named, triggers if has_fgs else [], obf, [w.rsplit('.', 1)[-1] for w in weird[:3]],
                    packed, bait, fin, chat and wallet, chat and wallet and secret, weekly, game, imp)


def _similar(a: str, b: str) -> bool:
    if not a or not b:
        return False
    if a == b:
        return True
    # normalized edit distance
    m, n = len(a), len(b)
    d = list(range(n + 1))
    for i in range(1, m + 1):
        prev, d[0] = d[0], i
        for j in range(1, n + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (a[i - 1] != b[j - 1]))
    return 1 - d[n] / max(m, n) >= 0.8


_desc_cache: dict = {}


def _descriptor_prefix_index(app: App) -> list:
    """Type descriptors only (strings like 'Lcom/foo/Bar;'), cached per app."""
    key = id(app)
    if key not in _desc_cache:
        _desc_cache[key] = [s for s in app.dex if s.startswith("L") and s.endswith(";")]
    return _desc_cache[key]


# ---------------------------------------------------------------- decision (spec 5)
@dataclass
class Verdict:
    level: str             # D / C / N / S
    score: int
    reasons: list          # plain-language, ordered, <= 3 shown
    audit: str


def decide(app: App, f: Features, trusted: bool = False, system: bool = False) -> Verdict:
    R = rules()
    T = R["reason_text"]
    if system:
        return Verdict("S", 0, [], "T3 system")
    vetoes = []
    fin_money = any(g in f.fin_groups for g in ("G1_deposit", "G2_withdraw"))
    fin_crypto = any(g in f.fin_groups for g in R["v1_required_any"])
    if not trusted and len(f.fin_groups) >= R["thresholds"]["v1_min_groups"] and fin_money and fin_crypto:
        vetoes.append(("V1", T["V1"]))
    if not trusted and f.im_wallet and f.im_secret:
        vetoes.append(("V2", T["V2"]))
    if f.src in ("sideload", "app_installed") and set(f.ctrl) & set(R["v3_ctrl"]):
        vetoes.append(("V3", T["V3"]))
    if f.impersonates:
        vetoes.append(("V4", T["V4"].format(brand=f.impersonates)))

    n_ad = len(f.ad_networks)
    a = {
        "A1": min(4, (3 if n_ad >= 7 else 2 if n_ad >= 4 else 1 if n_ad >= 2 else 0) + (1 if f.mediation else 0)),
        "A2": 3 if f.ooa else (1 if f.ooa_named else 0),
        "A3": 2 if len(f.triggers) >= 2 else (1 if f.triggers else 0),
        "A4": 2 if f.obf else 0,
        "A5": 1 if f.bait else 0,
        "A6": 3 if f.bait and (f.snoop or f.ctrl) else 0,
        "A7": 0,  # behavioural; not available offline
        "A8": 2 if f.src in ("sideload", "app_installed") else 0,
        "A9": 2 if f.packed else 0,
        "A10": 2 if f.weekly_sub else 0,
    }
    score = sum(a.values())
    abuse = a["A2"] >= 1 or a["A3"] >= 1 or a["A4"] or a["A6"] or a["A7"] or a["A9"]
    th = R["thresholds"]
    if vetoes:
        level = "D"
    elif score >= th["caution_score"] and abuse:
        level = "C"
    elif score >= th["notice_score"] or n_ad >= th["notice_ad_networks"]:
        level = "N"
    else:
        level = "S"
    capped = ""
    if level in ("C", "D") and not vetoes and (trusted or (f.game and not (a["A2"] >= 3 or a["A6"] or a["A9"]))):
        level, capped = "N", " capped:" + ("T1" if trusted else "T2")
    if app.partial and level == "C" and not vetoes:
        level, capped = "N", capped + " partial"

    texts = {
        "A1": T["A1"].format(n=n_ad), "A2": T["A2"], "A3": T["A3"], "A4": T["A4"],
        "A5": T["A5"].format(cat=R["bait_names"].get(f.bait or "", "")), "A6": T["A6"], "A8": T["A8"],
        "A9": T["A9"], "A10": T["A10"],
    }
    ordered = [t for _, t in vetoes] + [texts[k] for k, v in sorted(a.items(), key=lambda kv: -kv[1]) if v > 0 and k in texts]
    audit = (f"{level} score={score} " + " ".join(f"{k}={v}" for k, v in a.items() if v)
             + (" veto=" + ",".join(v for v, _ in vetoes) if vetoes else "") + capped
             + f" | src={f.src} n_ad={n_ad} ad={','.join(f.ad_networks)} trig={','.join(f.triggers)} ctrl={','.join(f.ctrl)}"
             + f" snoop={','.join(f.snoop)} fin={','.join(f.fin_groups)} bait={f.bait} obf={f.obf_examples} game={f.game}"
             + f" packed={f.packed} partial={app.partial} rules={R['version']}")
    return Verdict(level, score, ordered[:3], audit)


def assess(apks: list[Path], installer: str | None = None, trusted_certs: set | None = None) -> tuple[App, Features, Verdict]:
    app = extract(apks, installer)
    f = features(app)
    trusted = bool(trusted_certs) and app.cert in trusted_certs
    return app, f, decide(app, f, trusted=trusted)


if __name__ == "__main__":
    import sys
    paths = [Path(p) for p in sys.argv[1:]]
    apks = sorted(paths[0].glob("*.apk")) if len(paths) == 1 and paths[0].is_dir() else paths
    app, f, v = assess(apks)
    print(app.package, app.labels[:1], v.level, v.reasons)
    print(v.audit)
