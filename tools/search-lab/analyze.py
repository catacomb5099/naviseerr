# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["rapidfuzz"]
# ///
"""Join raw searches, the labelled sample and the title tags; print every number the decision doc needs.

    uv run analyze.py > raw/report.md
"""
import json, re, statistics as st
from collections import Counter, defaultdict
from pathlib import Path
from rapidfuzz import fuzz

RAW = Path(__file__).parent / "raw"
songs = {s["id"]: s for s in map(json.loads, (RAW / "songs.jsonl").read_text().splitlines())}
pool = {p["song"]: p for p in map(json.loads, (RAW / "pool.jsonl").read_text().splitlines())}
labels, requested = {}, {}
for f in sorted((RAW / "labels").glob("*.jsonl")):
    for line in f.read_text().splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        if "requested" in d:
            requested[d["song"]] = d["requested"]
        else:
            labels[(d["song"], d["id"])] = d
tags = json.loads((RAW / "title_tags.json").read_text()) if (RAW / "title_tags.json").exists() else []

# ---- port of TrackMatchingService (fuzzywuzzy -> rapidfuzz) ----
LIVEISH = {"live", "acoustic"}
def is_exact(lab, req):
    ver = "original" if lab["version"] == "unknown" else lab["version"]
    return lab["relevant"] and (ver == req or (ver in LIVEISH and req in LIVEISH))

def tm_normalize(t):
    t = t.lower()
    t = re.sub(r"\.(flac|mp3|m4a|aif|wav|ogg|aac|wma)$", "", t)
    t = re.sub(r"^\d{1,3}[.\s-]+", "", t); t = re.sub(r"[_\s-]\d{1,3}[_\s-]", " ", t)
    t = re.sub(r"\[.*?\]|\(.*?\)|\{.*?\}", "", t)
    t = re.sub(r"(320kbps|flac|mp3|wav|m4a|lossless|cd\s*\d+)", "", t); t = re.sub(r"\d{4}", "", t)
    t = re.sub(r"(remix|edit|version|remaster)", "", t)
    t = re.sub(r"[_]+", " ", t); t = re.sub(r"[-]+", " ", t); t = re.sub(r"[^a-z0-9\s]", "", t)
    return re.sub(r"\s+", " ", t).strip()

def tm_is_match(query, path):
    fn = re.split(r"[/\\]", path)[-1]
    nq, nf = tm_normalize(query), tm_normalize(fn)
    both = False
    if "-" in query:
        a, b = [p.strip() for p in query.split("-", 1)]
        both = tm_normalize(a) in nf and tm_normalize(b) in nf
    return fuzz.token_sort_ratio(nq, nf) >= 75 or fuzz.partial_ratio(nq, nf) >= 85 or both

DJPOOL = re.compile(r"\b(clean|dirty|intro|outro|acapella|acappella|transition|redrum|refix|quick hit|hype)\b|\b\d{1,2}[ab]\s+\d{2,3}\b|dj-promo|djpool|dj pool", re.I)
VERSION_WORDS = re.compile(r"\b(live|remix|rmx|mix|acoustic|unplugged|instrumental|karaoke|cover|tribute|sped up|slowed|nightcore|8d|demo|edit|dub|mashup|bootleg|session|concert|tour)\b", re.I)

def tokens(s):
    return set(re.findall(r"[a-z0-9]+", s.lower()))

def signals(song, f, req):
    path = f["path"]; fn = path.split("/")[-1]
    art = tokens(song["artists"][0] or "") if song["artists"] else set()
    tit = tokens(song["title"].split("(")[0].split(" - ")[0])
    ft = tokens(path)
    d = song["duration"]; L = f.get("length")
    return {
        "dur3": L is not None and d is not None and abs(L - d) <= 3,
        "dur10": L is not None and d is not None and abs(L - d) <= 10,
        "artist_in_path": bool(art) and art <= ft,
        "title_in_name": bool(tit) and tit <= tokens(fn),
        "title_in_path": bool(tit) and tit <= ft,
        "no_version_words": not VERSION_WORDS.search(fn) if req == "original" else True,
        "lossless_or_320": f["ext"] == "flac" or (f.get("bitRate") or 0) >= 320,
        "no_djpool": not DJPOOL.search(fn),
    }

out = []
P = out.append
P(f"# Search lab report\n\nSongs: {len(songs)}, searched: {len(pool)}, labelled files: {len(labels)}, songs with a requested-version tag: {len(requested)}\n")

# ---- per-variant search stats ----
P("## Search shapes\n")
vs = defaultdict(list)
for p in pool.values():
    for v, s in p["variants"].items():
        vs[v].append(s)
P("| variant | searches | median files | median peers | zero files | response limit hit | median seconds |\n|---|---|---|---|---|---|---|")
for v in "ABC":
    if not vs[v]:
        continue
    fc = [s["file_count"] or 0 for s in vs[v]]
    P(f"| {v} | {len(fc)} | {int(st.median(fc))} | {int(st.median([s['response_count'] or 0 for s in vs[v]]))} | {sum(1 for x in fc if x == 0)} | "
      f"{sum(1 for s in vs[v] if 'ResponseLimitReached' in (s['state'] or ''))} | {st.median([s['seconds'] for s in vs[v]]):.1f} |")
P("")
P(f"Songs where A and B were different queries: {sum(1 for p in pool.values() if 'B' in p['variants'])}. Songs starved (A under 20 files): {sum(1 for p in pool.values() if (p['variants'].get('A', {}).get('file_count') or 0) < 20)}.\n")

# ---- label-based metrics per variant ----
P("## Relevance of what came back (labelled sample)\n")
per_song = defaultdict(lambda: defaultdict(lambda: Counter()))
for (sid, fid), lab in labels.items():
    p = pool.get(sid)
    if not p:
        continue
    f = next((x for x in p["files"] if x["id"] == fid), None)
    if not f:
        continue
    req = requested.get(sid, "original")
    exact = is_exact(lab, req)
    for v in f["variants"]:
        c = per_song[sid][v]
        c["files"] += 1; c["relevant"] += lab["relevant"]; c["exact"] += exact
per_song = {k: dict(v) for k, v in per_song.items()}
P("| variant | songs | files labelled | relevant | exact | precision (exact/files) | songs with >=1 exact | songs with 0 relevant |\n|---|---|---|---|---|---|---|---|")
for v in "ABC":
    rows = [c[v] for c in per_song.values() if v in c]
    if not rows:
        continue
    files = sum(r["files"] for r in rows); ex = sum(r["exact"] for r in rows)
    P(f"| {v} | {len(rows)} | {files} | {sum(r['relevant'] for r in rows)} | {ex} | {ex / files:.0%} | {sum(1 for r in rows if r['exact'])} | {sum(1 for r in rows if r['relevant'] == 0)} |")
P("")

P("### By song group (bare query A only)\n")
P("| group | songs | files labelled | precision (exact) | songs with 0 relevant in any shape |\n|---|---|---|---|---|")
for g in ("charts", "catalogue"):
    sids = [sid for sid in pool if songs[sid].get("group") == g]
    rows_ = [per_song[sid]["A"] for sid in sids if "A" in per_song.get(sid, {})]
    files_ = sum(r["files"] for r in rows_); ex_ = sum(r["exact"] for r in rows_)
    none = sum(1 for sid in sids if sid in per_song and sum(c["relevant"] for c in per_song[sid].values()) == 0)
    P(f"| {g} | {len(sids)} | {files_} | {ex_ / max(1, files_):.0%} | {none} |")
P("")
P("### By playlist (bare query A only)\n")
P("| playlist | songs | precision (exact) | songs with 0 relevant in any shape |\n|---|---|---|---|")
for pl in dict.fromkeys(s_["playlist"] for s_ in songs.values()):
    sids = [sid for sid in pool if songs[sid]["playlist"] == pl]
    rows_ = [per_song[sid]["A"] for sid in sids if "A" in per_song.get(sid, {})]
    files_ = sum(r["files"] for r in rows_); ex_ = sum(r["exact"] for r in rows_)
    none = sum(1 for sid in sids if sid in per_song and sum(c["relevant"] for c in per_song[sid].values()) == 0)
    P(f"| {pl} | {len(sids)} | {ex_ / max(1, files_):.0%} | {none} |")
P("")
P("### Songs with no relevant file in any shape (probably not on Soulseek, or the query is broken)\n")
for sid in pool:
    if sid in per_song and sum(c["relevant"] for c in per_song[sid].values()) == 0:
        P(f"- [{songs[sid].get('group')}] {songs[sid]['title']!r} — {songs[sid]['artists'][0]}: " + ", ".join(f"{v}={s_['file_count']}" for v, s_ in pool[sid]["variants"].items()))
P("")

P("## Searches the Soulseek server silently drops\n")
P("Bare query returned 0 files after the full timeout while the title-only query returned hundreds. Live re-tests show this is deterministic for the artist name, not load (see notes).\n")
drop = [(sid, p) for sid, p in pool.items() if p["variants"].get("A", {}).get("file_count") == 0 and (p["variants"].get("C", {}).get("file_count") or 0) >= 100]
P(f"{len(drop)} of {len(pool)} searched songs. Exact files found by the title-only query for them: {sum(per_song.get(sid, {}).get('C', Counter())['exact'] for sid, _ in drop)}.\n")
for sid, p in drop:
    P(f"- {p['variants']['A']['query']!r}: title-only {p['variants']['C']['file_count']} files, {per_song.get(sid, {}).get('C', Counter())['exact']} exact in the labelled sample")
P("")

# ---- loose-search hypothesis ----
P("## Loose-search hypothesis: does the bare query already hold the requested version?\n")
qual = [sid for sid in pool if requested.get(sid, "original") != "original"]
hit = [sid for sid in qual if per_song.get(sid, {}).get("A", Counter())["exact"] > 0]
hitB = [sid for sid in qual if per_song.get(sid, {}).get("B", Counter())["exact"] > 0]
P(f"Songs whose YouTube title asks for a non-original version: {len(qual)} ({Counter(requested[s] for s in qual)}). "
  f"Bare query A had at least one exact file for {len(hit)} of them; query B (qualifier kept) for {len(hitB)}.\n")
for sid in qual:
    ps = per_song.get(sid, {})
    P(f"- {songs[sid]['title']!r} — {songs[sid]['artists'][0]}: requested {requested[sid]}, A exact {ps.get('A', Counter())['exact']}, B exact {ps['B']['exact'] if 'B' in ps else 'n/a'}")
P("")

# ---- picker signals ----
P("## Picker signals (labelled files, requested version known)\n")
rows = []
for (sid, fid), lab in labels.items():
    p = pool.get(sid); f = next((x for x in p["files"] if x["id"] == fid), None) if p else None
    if not f:
        continue
    req = requested.get(sid, "original")
    exact = is_exact(lab, req)
    q = (p["variants"].get("A") or next(iter(p["variants"].values())))["query"]
    sig = signals(songs[sid], f, req)
    sig["matcher_on_path"] = tm_is_match(q, f["path"].replace("/", " "))
    rows.append((exact, lab["relevant"], sig, tm_is_match(q, f["path"]), f, sid, lab))
P(f"Base rate: {sum(r[0] for r in rows)} exact of {len(rows)} labelled ({sum(r[0] for r in rows) / max(1, len(rows)):.0%}); relevant (any version) {sum(r[1] for r in rows)}.\n")
P("| signal | fires on | precision (exact) | recall (exact) |\n|---|---|---|---|")
def pr(pred):
    tp = sum(1 for r in rows if pred(r) and r[0]); fp = sum(1 for r in rows if pred(r) and not r[0]); fn = sum(1 for r in rows if not pred(r) and r[0])
    return tp + fp, tp / max(1, tp + fp), tp / max(1, tp + fn)
for name in ("dur3", "dur10", "artist_in_path", "title_in_name", "title_in_path", "no_version_words", "lossless_or_320"):
    n, p_, r_ = pr(lambda r, name=name: r[2][name]); P(f"| {name} | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3]); P(f"| **today's matcher** isMatch(A query) | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[2]["matcher_on_path"]); P(f"| today's matcher fed the folder path too | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[2]["matcher_on_path"] and r[2]["no_version_words"]); P(f"| matcher on path & no_version_words | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[2]["matcher_on_path"] and r[2]["no_version_words"] and r[2]["dur10"]); P(f"| matcher on path & no_version_words & dur10 | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[2]["matcher_on_path"] and r[2]["no_version_words"] and (r[2]["dur10"] or r[4].get("length") is None)); P(f"| ... & (dur10 or length unknown) | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[2]["artist_in_path"] and r[2]["title_in_name"] and r[2]["no_version_words"]); P(f"| artist_in_path & title_in_name & no_version_words | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3] and r[2]["no_version_words"]); P(f"| today's matcher & no_version_words | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3] and r[2]["no_version_words"] and r[2]["dur10"]); P(f"| today's matcher & no_version_words & dur10 | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3] and r[2]["no_version_words"] and r[2]["no_djpool"]); P(f"| **proposed filter**: today's matcher & no_version_words & no_djpool | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3] and r[2]["no_version_words"] and r[2]["no_djpool"] and r[2]["artist_in_path"]); P(f"| proposed filter & artist_in_path | {n} | {p_:.0%} | {r_:.0%} |")
n, p_, r_ = pr(lambda r: r[3] and r[2]["no_version_words"] and r[2]["no_djpool"] and r[2]["artist_in_path"] and (r[2]["dur10"] or r[4].get("length") is None)); P(f"| proposed filter & artist_in_path & (dur10 or unknown length) | {n} | {p_:.0%} | {r_:.0%} |")
songs_any_exact = sum(1 for sid in per_song if any(c["exact"] for c in per_song[sid].values()))
songs_A_exact = sum(1 for sid in per_song if per_song[sid].get("A", Counter())["exact"])
P(f"\nSongs with at least one exact file in the labelled sample, any shape: {songs_any_exact} of {len(per_song)}; via the bare query alone: {songs_A_exact}.")
P("")
wrong = [r for r in rows if r[3] and not r[0]]
P(f"### Files today's matcher accepts that are not the requested version ({len(wrong)})\n")
P(f"By label: {Counter(('irrelevant' if not r[1] else r[6]['version']) for r in wrong)}\n")
seen_ = Counter()
for r in wrong:
    if seen_[r[5]] >= 2 or sum(seen_.values()) >= 40: 
        if sum(seen_.values()) >= 40: break
        continue
    seen_[r[5]] += 1
    P(f"- {songs[r[5]]['title']!r} — {songs[r[5]]['artists'][0]}: `{r[4]['path']}` → {r[6]['version']}{'' if r[1] else ' (not this song)'}")
P("")
wc = Counter()
for r in wrong:
    for w in set(m.lower() for m in VERSION_WORDS.findall(r[4]["path"].split("/")[-1])):
        wc[w] += 1
P(f"Version words present in the accepted-but-wrong filenames: {wc.most_common(20)}; with no version word at all: {sum(1 for r in wrong if not VERSION_WORDS.search(r[4]['path'].split('/')[-1]))}\n")
missed = [r for r in rows if r[0] and not r[3]]
P(f"### Exact files today's matcher rejects ({len(missed)})\n")
seen_ = Counter()
for r in missed:
    if seen_[r[5]] >= 2 or sum(seen_.values()) >= 25:
        if sum(seen_.values()) >= 25: break
        continue
    seen_[r[5]] += 1
    P(f"- {songs[r[5]]['title']!r} — {songs[r[5]]['artists'][0]}: `{r[4]['path']}`")
P("")

# ---- duration gap (video vs audio) ----
P("## Duration: YouTube length vs file length, exact files only\n")
gaps = [abs(r[4]["length"] - songs[r[5]]["duration"]) for r in rows if r[0] and r[4].get("length") and songs[r[5]]["duration"]]
if gaps:
    P(f"n={len(gaps)}, median gap {st.median(gaps):.0f}s, within 3s: {sum(g <= 3 for g in gaps) / len(gaps):.0%}, within 10s: {sum(g <= 10 for g in gaps) / len(gaps):.0%}, within 30s: {sum(g <= 30 for g in gaps) / len(gaps):.0%}")
    by_type = defaultdict(list)
    for r in rows:
        if r[0] and r[4].get("length") and songs[r[5]]["duration"]:
            by_type[songs[r[5]]["video_type"]].append(abs(r[4]["length"] - songs[r[5]]["duration"]))
    for k, g in by_type.items():
        P(f"- {k}: n={len(g)}, within 10s {sum(x <= 10 for x in g) / len(g):.0%}")
P("")

# ---- title fragments ----
P("## Title fragments (Sonnet tags)\n")
if tags:
    noise = Counter(t["fragment"].lower() for t in tags if t["tag"] == "noise")
    meaning = Counter(t["fragment"].lower() for t in tags if t["tag"] == "meaningful")
    P(f"Fragments tagged noise ({sum(noise.values())}): " + ", ".join(f"`{k}`×{v}" for k, v in noise.most_common(40)))
    P("")
    P(f"Fragments tagged meaningful ({sum(meaning.values())}): " + ", ".join(f"`{k}`×{v}" for k, v in meaning.most_common(40)))
P("")

# ---- starved ----
P("## Starved songs (every shape under 20 files)\n")
for sid, p in pool.items():
    if all((s["file_count"] or 0) < 20 for s in p["variants"].values()):
        P(f"- {songs[sid]['title']!r} — {songs[sid]['artists'][0]} [{songs[sid]['playlist']}]: " + ", ".join(f"{v}={s['file_count']}" for v, s in p["variants"].items()))
P("")
P("## Speed\n")
secs = [s["seconds"] for p in pool.values() for s in p["variants"].values()]
if secs:
    P(f"n={len(secs)}, median {st.median(secs):.1f}s, p90 {sorted(secs)[int(len(secs) * .9)]:.1f}s, max {max(secs):.1f}s. Searches hitting slskd's response limit (all results in well under the 10s timeout): {sum(1 for p in pool.values() for s in p['variants'].values() if 'ResponseLimitReached' in (s['state'] or ''))}.")
P("## Label quality\n")
import subprocess
if (RAW / "audit" / "sonnet.jsonl").exists():
    P("A 225-file sample was labelled a second time, blind, by Sonnet 5:")
    P("- Haiku 4.5 labels vs Sonnet audit: " + subprocess.run(["python3", "audit.py", "labels_haiku"], capture_output=True, text=True, cwd=str(RAW.parent)).stdout.splitlines()[0])
    P("- Sonnet 5 labels vs Sonnet audit (same model, independent run): " + subprocess.run(["python3", "audit.py", "labels"], capture_output=True, text=True, cwd=str(RAW.parent)).stdout.splitlines()[0])
    P("Haiku failed the 5% bar and its labels were discarded; every label in this report is from Sonnet 5.")
print("\n".join(out))
