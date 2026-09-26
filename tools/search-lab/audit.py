"""Compare the Sonnet audit labels with the Haiku labels for the same files."""
import json, glob, collections, sys
R = "raw/"
LABELS = sys.argv[1] if len(sys.argv) > 1 else "labels"
haiku = {}
req_h = {}
for f in sorted(glob.glob(R + LABELS + "/batch_*.jsonl")):
    for l in open(f):
        if not l.strip(): continue
        d = json.loads(l)
        if "requested" in d: req_h[d["song"]] = d["requested"]
        else: haiku[(d["song"], d["id"])] = d
son = {}; req_s = {}
for l in open(R + "audit/sonnet.jsonl"):
    if not l.strip(): continue
    d = json.loads(l)
    if "requested" in d: req_s[d["song"]] = d["requested"]
    else: son[(d["song"], d["id"])] = d
def norm(v): return "original" if v == "unknown" else v
n = rel_dis = ver_dis = 0; ex = []
inp = {(s["song"], f["id"]): (s, f) for s in json.load(open(R + "audit/sample.json")) for f in s["files"]}
for k, d in son.items():
    h = haiku.get(k)
    if not h: continue
    n += 1
    if h["relevant"] != d["relevant"]:
        rel_dis += 1; ex.append(("REL", inp[k][0]["title"], inp[k][1]["path"], "haiku", h["relevant"], h["version"], "sonnet", d["relevant"], d["version"]))
    elif d["relevant"] and norm(h["version"]) != norm(d["version"]):
        ver_dis += 1; ex.append(("VER", inp[k][0]["title"], inp[k][1]["path"], "haiku", h["version"], "sonnet", d["version"]))
rq = sum(1 for s in req_s if s in req_h and req_h[s] != req_s[s])
print(f"files compared {n}; relevant disagreements {rel_dis} ({rel_dis/n:.1%}); version disagreements among relevant {ver_dis} ({ver_dis/n:.1%}); requested-version disagreements {rq}/{len(req_s)}")
for e in ex: print(" ", e)
