# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = []
# ///
"""Export the labelled sample as a regression fixture for the Java matcher tests.
No peer usernames are present (reduce.py already stripped them).

    uv run export_fixture.py ../../src/test/resources/search-lab/labelled.jsonl.gz
"""
import gzip, json, sys
from pathlib import Path
RAW = Path(__file__).parent / "raw"
out = Path(sys.argv[1]); out.parent.mkdir(parents=True, exist_ok=True)
songs = {s["id"]: s for s in map(json.loads, (RAW / "songs.jsonl").read_text().splitlines())}
pool = {p["song"]: p for p in map(json.loads, (RAW / "pool.jsonl").read_text().splitlines())}
requested, labels = {}, {}
for f in sorted((RAW / "labels").glob("batch_*.jsonl")):
    for line in f.read_text().splitlines():
        if not line.strip(): continue
        d = json.loads(line)
        if "requested" in d: requested[d["song"]] = d["requested"]
        else: labels[(d["song"], d["id"])] = d
LIVEISH = {"live", "acoustic"}
n = 0
with gzip.open(out, "wt", encoding="utf-8") as fh:
    for (sid, fid), lab in sorted(labels.items()):
        p = pool.get(sid); s = songs.get(sid)
        if not p or not s: continue
        f = next((x for x in p["files"] if x["id"] == fid), None)
        if not f: continue
        req = requested.get(sid, "original")
        ver = "original" if lab["version"] == "unknown" else lab["version"]
        exact = lab["relevant"] and (ver == req or (ver in LIVEISH and req in LIVEISH))
        q = (p["variants"].get("A") or next(iter(p["variants"].values())))["query"]
        fh.write(json.dumps({"song": sid, "title": s["title"], "artist": s["artists"][0] if s["artists"] else None,
                             "duration": s["duration"], "query": q,
                             "request": f"{s['title']} - {s['artists'][0]}" if s["artists"] else s["title"], "requested": req, "path": f["path"],
                             "length": f.get("length"), "bitRate": f.get("bitRate"), "ext": f["ext"],
                             "relevant": lab["relevant"], "version": ver, "exact": exact}, ensure_ascii=False) + "\n")
        n += 1
print(f"{n} rows -> {out}")
