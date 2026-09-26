# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["ytmusicapi==1.12.2", "httpx>=0.27,<1", "certifi"]
# ///
"""Search lab collector: pull songs from YouTube Music playlists, search each one on slskd
in up to three query shapes, store the raw responses. Never starts a transfer.

    uv run collect.py songs     # -> raw/songs.jsonl
    uv run collect.py search    # -> raw/searches.jsonl (resumable, one search at a time)
"""
import json, os, re, sys, time
from pathlib import Path

HERE = Path(__file__).parent
RAW = HERE / "raw"
RAW.mkdir(exist_ok=True)

# Zscaler on this laptop: build a CA bundle once, point both stacks at it before importing them.
CA = RAW / "ca.pem"
if not CA.exists():
    import certifi
    zs = Path.home() / ".gh-catacombs" / "zscaler-root-ca.crt"
    CA.write_bytes(Path(certifi.where()).read_bytes() + (zs.read_bytes() if zs.exists() else b""))
os.environ.setdefault("REQUESTS_CA_BUNDLE", str(CA))
os.environ.setdefault("SSL_CERT_FILE", str(CA))

import httpx  # noqa: E402

PLAYLISTS = {
    "Top 100 Songs Global": "PL4fGSI1pDJn6puJdseH2Rt9sMvt9E2M4i",
    "Top 100 Songs United Kingdom": "PL4fGSI1pDJn6_f5P3MnzXg9l3GDfnSlXa",
    "Top 100 Music Videos Global": "PL4fGSI1pDJn5kI81J1fYWK5eZRl1zJ5kM",
    "Best Rock & Alternative Songs 1990-1999": "PLplXQ2cg9B_rwkVy_F5GX7LkXYQhs3BFg",
    "Classic Soul Music (RHINO)": "PLmXxqSJJq-yVfPqdjmaZ_qO6gGNOl1vog",
}

# ---- query shapes (port of naviseerr's SearchQueryTiers, PR #26) ----
NOISE = re.compile(r"\b(official hd remastered video|official music video|official lyric video|official video|"
                   r"official audio|remastered video|music video|lyric video|visuali[sz]er|lyrics|official|"
                   r"audio|video|hd|hq|4k)\b", re.I)
GROUP = re.compile(r"\s*[(\[{]([^()\[\]{}]*)[)\]}]")
SEGMENT = re.compile(r"\s+(?:[-–—|]\s+)+")

def strip_noise(s: str) -> str:
    return re.sub(r"\s+", " ", NOISE.sub("", re.sub(r"\s+", " ", s))).strip()

def without_noise(name: str) -> str:
    def repl(m):
        inner = strip_noise(m.group(1))
        return f" ({inner})" if inner else ""
    prev = None
    while prev != name:
        prev, name = name, GROUP.sub(repl, name)
    segs = SEGMENT.split(name)
    kept = [s for i, s in enumerate(segs) if i in (0, len(segs) - 1) or strip_noise(s)]
    return re.sub(r"\s+", " ", " - ".join(kept)).strip()

def bare(name: str) -> str:
    prev = None
    while prev != name:
        prev, name = name, GROUP.sub("", name)
    segs = [s.strip() for s in SEGMENT.split(name) if s.strip()]
    if len(segs) < 2:
        return " ".join(segs)
    artist = segs[-1]
    title = [s for s in segs[:-1] if s.lower() != artist.lower()]
    return f"{' '.join(title)} - {artist}" if title else artist

def variants(song) -> dict:
    """A bare title-artist, B noise-stripped (keeps remix/live), C title only (conditional)."""
    name = f"{song['title']} - {song['artist']}" if song["artist"] else song["title"]
    b = without_noise(name) or name
    return {"A": bare(b), "B": b, "C": bare(f"{song['title']} - x").removesuffix(" - x")}

# ---- songs ----
def cmd_songs():
    from ytmusicapi import YTMusic
    yt = YTMusic()
    out, seen = [], set()
    for pl_name, pl_id in PLAYLISTS.items():
        pl = yt.get_playlist(pl_id, limit=200)
        for t in pl.get("tracks", []):
            vid = t.get("videoId")
            if not vid or vid in seen or not t.get("title"):
                continue
            seen.add(vid)
            out.append({
                "id": vid, "playlist": pl_name, "title": t["title"],
                "artist": (t.get("artists") or [{}])[0].get("name"),
                "artists": [a.get("name") for a in (t.get("artists") or [])],
                "album": (t.get("album") or {}).get("name"),
                "duration": t.get("duration_seconds"),
                "video_type": t.get("videoType"),
            })
        print(f"{pl_name}: {len(pl.get('tracks', []))} tracks", file=sys.stderr)
    (RAW / "songs.jsonl").write_text("".join(json.dumps(s) + "\n" for s in out))
    print(f"{len(out)} unique songs", file=sys.stderr)

# ---- slskd ----
def env():
    kv = dict(l.split("=", 1) for l in (HERE.parent.parent / ".env").read_text().splitlines() if "=" in l)
    return kv["SLSKD_URL"].rstrip("/"), kv["SLSKD_API_KEY"]

MAX_FILES = 500

def get_json(c: httpx.Client, url: str, **kw):
    """slskd behind a proxy occasionally answers a poll with an empty or HTML body; retry a few times."""
    for attempt in range(5):
        try:
            r = c.get(url, **kw)
            r.raise_for_status()
            return r.json()
        except (httpx.HTTPError, ValueError) as e:
            if attempt == 4:
                raise
            print(f"retry {attempt + 1} on {url.split('/api/v0')[-1]}: {type(e).__name__}", file=sys.stderr)
            time.sleep(3)

def run_search(c: httpx.Client, base: str, text: str) -> dict:
    t0 = time.time()
    r = c.post(f"{base}/searches", json={"searchText": text, "searchTimeout": 10000})
    r.raise_for_status()
    sid = r.json()["id"]
    try:
        while True:
            time.sleep(2)
            st = get_json(c, f"{base}/searches/{sid}")
            if st.get("isComplete") or time.time() - t0 > 60:
                break
        full = get_json(c, f"{base}/searches/{sid}", params={"includeResponses": "true"})
    finally:
        c.delete(f"{base}/searches/{sid}")
    kept, n = [], 0
    for resp in full.get("responses") or []:
        files = []
        for f in resp.get("files") or []:
            if n >= MAX_FILES:
                break
            files.append({k: f.get(k) for k in ("filename", "size", "bitRate", "length", "extension", "sampleRate", "bitDepth")})
            n += 1
        if files:
            kept.append({"username": resp.get("username"), "queueLength": resp.get("queueLength"),
                         "uploadSpeed": resp.get("uploadSpeed"), "hasFreeUploadSlot": resp.get("hasFreeUploadSlot"),
                         "files": files})
    return {"query": text, "seconds": round(time.time() - t0, 1), "state": full.get("state"),
            "file_count": full.get("fileCount"), "locked_count": full.get("lockedFileCount"),
            "response_count": full.get("responseCount"), "responses": kept}

def cmd_search():
    base, key = env()
    songs = [json.loads(l) for l in (RAW / "songs.jsonl").read_text().splitlines()]
    out = RAW / "searches.jsonl"
    done = {}
    if out.exists():
        for l in out.read_text().splitlines():
            d = json.loads(l)
            done[(d["song"], d["variant"])] = d
    c = httpx.Client(headers={"X-API-Key": key}, timeout=30)
    errors = 0
    with out.open("a") as fh:
        for i, s in enumerate(songs):
            v = variants(s)
            plan = [("A", v["A"])]
            if v["B"] != v["A"]:
                plan.append(("B", v["B"]))
            for var, text in plan + [("C", v["C"])]:
                if (s["id"], var) in done:
                    continue
                if var == "C":
                    a = done.get((s["id"], "A"))
                    if not a or (a.get("file_count") or 0) >= 20 or v["C"] == v["A"]:
                        continue
                try:
                    res = run_search(c, base, text)
                    errors = 0
                except Exception as e:
                    if isinstance(e, httpx.HTTPStatusError) and e.response.status_code == 429:
                        sys.exit("429 from slskd, stopping")
                    errors += 1
                    print(f"{type(e).__name__} on {text!r}: {str(e)[:120]}", file=sys.stderr)
                    if errors >= 3:
                        sys.exit("3 consecutive errors, stopping")
                    time.sleep(30)
                    continue
                rec = {"song": s["id"], "variant": var, "at": time.strftime("%Y-%m-%dT%H:%M:%S"), **res}
                fh.write(json.dumps(rec) + "\n"); fh.flush()
                done[(s["id"], var)] = rec
                print(f"[{i+1}/{len(songs)}] {var} {text!r}: {res['file_count']} files / {res['response_count']} peers in {res['seconds']}s", file=sys.stderr)
                time.sleep(5)

if __name__ == "__main__":
    (RAW / "collect.pid").write_text(str(os.getpid()))
    {"songs": cmd_songs, "search": cmd_search}[sys.argv[1]]()
