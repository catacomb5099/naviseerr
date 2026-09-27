# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = []
# ///
"""Turn raw slskd responses into labelling batches: strip peers, keep audio only, dedupe per song,
cap the sample per song. Output raw/pool.jsonl (all unique audio files per song) and raw/batches/*.json.

    uv run reduce.py [songs_per_batch=15] [files_per_song=60]
"""
import hashlib, json, random, re, sys
from collections import defaultdict
from pathlib import Path

RAW = Path(__file__).parent / "raw"
AUDIO = {"flac", "mp3", "m4a", "ogg", "opus", "wav", "aac", "wma", "aif", "aiff", "alac", "ape", "wv", "dsf"}
PER_BATCH = int(sys.argv[1]) if len(sys.argv) > 1 else 15
PER_SONG = int(sys.argv[2]) if len(sys.argv) > 2 else 60
PARTIAL = "partial" in sys.argv  # collection still running: drop the song currently being searched

def ext_of(name: str) -> str:
    m = re.search(r"\.([a-z0-9]{2,5})$", name.lower())
    return m.group(1) if m else ""

def clean_path(p: str) -> str:
    # slskd paths often start with an "@@xxxxx" share alias; drop it, keep folders (album hints).
    parts = re.split(r"[\\/]+", p)
    if parts and parts[0].startswith("@@"):
        parts = parts[1:]
    return "/".join(parts[-3:])  # last two folders + file is enough context

songs = {s["id"]: s for s in map(json.loads, (RAW / "songs.jsonl").read_text().splitlines())}
lines = (RAW / "searches.jsonl").read_text().splitlines()
skip = {json.loads(lines[-1])["song"]} if PARTIAL and lines else set()
by_song = defaultdict(dict)   # song -> key -> file
variant_stats = defaultdict(dict)
non_audio = 0
for line in lines:
    d = json.loads(line)
    if d["song"] in skip:
        continue
    variant_stats[d["song"]][d["variant"]] = {k: d.get(k) for k in ("query", "seconds", "state", "file_count", "response_count", "locked_count")}
    for r in d["responses"]:
        for f in r["files"]:
            e = ext_of(f["filename"])
            if e not in AUDIO:
                non_audio += 1
                continue
            path = clean_path(f["filename"])
            key = re.sub(r"\s+", " ", path.lower())
            cur = by_song[d["song"]].setdefault(key, {
                "id": hashlib.sha1(key.encode()).hexdigest()[:10], "path": path, "ext": e,
                "length": f.get("length"), "bitRate": f.get("bitRate"), "size": f.get("size"), "variants": [], "copies": 0})
            cur["copies"] += 1
            if d["variant"] not in cur["variants"]:
                cur["variants"].append(d["variant"])

rng = random.Random(7)
pool_lines, batches, batch = [], [], []
for sid, s in songs.items():
    if sid not in variant_stats:
        continue
    files = sorted(by_song[sid].values(), key=lambda f: -f["copies"])
    pool_lines.append(json.dumps({"song": sid, "variants": variant_stats[sid], "unique_audio": len(files), "files": files}))
    sample = files[:PER_SONG // 2] + rng.sample(files[PER_SONG // 2:], min(PER_SONG - PER_SONG // 2, max(0, len(files) - PER_SONG // 2)))
    batch.append({"song": sid, "title": s["title"], "artists": s["artists"], "album": s["album"], "duration": s["duration"],
                  "files": [{k: f[k] for k in ("id", "path", "length", "bitRate", "ext")} for f in sample]})
    if len(batch) >= PER_BATCH:
        batches.append(batch); batch = []
if batch:
    batches.append(batch)

(RAW / "pool.jsonl").write_text("\n".join(pool_lines) + "\n")
if "poolonly" in sys.argv:  # refresh the pool without touching batches that are already labelled
    print(f"{len(pool_lines)} songs in pool; batches untouched"); sys.exit(0)
bdir = RAW / "batches"; bdir.mkdir(exist_ok=True)
for p in bdir.glob("*.json"): p.unlink()
full = len(batches) - (1 if PARTIAL and batches and len(batches[-1]) < PER_BATCH else 0)
for i, b in enumerate(batches[:full]):
    (bdir / f"batch_{i:02d}.json").write_text(json.dumps(b, indent=0, ensure_ascii=False))
tot = sum(len(s["files"]) for b in batches for s in b)
print(f"{len(pool_lines)} songs, {sum(len(v) for v in by_song.values())} unique audio files ({non_audio} non-audio dropped), {len(batches)} batches, {tot} files to label")
