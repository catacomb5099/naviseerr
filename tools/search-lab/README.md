# Search lab

Measures, against the real slskd, which query shape returns the requested song and how well the candidate
picker tells the right file from the wrong one. Never starts a transfer. Findings from the first run are in
`docs/decisions/soulseek-search-lab-26-09-2026.md`.

## Run

Needs `uv`, Python 3.12, and a `.env` at the repo root with `SLSKD_URL` and `SLSKD_API_KEY` (the key is never
printed). On a machine behind Zscaler the scripts build a CA bundle into `raw/ca.pem` automatically.

```bash
uv run collect.py songs      # playlists -> raw/songs.jsonl (edit PLAYLISTS in collect.py)
uv run collect.py search     # one slskd search at a time, resumable -> raw/searches.jsonl
uv run reduce.py 15 60       # strip peers, dedupe, sample 60 files per song -> raw/pool.jsonl, raw/batches/
```

Label each `raw/batches/batch_NN.json` with a Claude agent following `prompts/label_files.md`, writing
`raw/labels/batch_NN.jsonl`. Use Sonnet 5 or better: Haiku 4.5 disagreed with an audit on 18.5% of files.
Then:

```bash
uv run analyze.py > raw/report.md                                        # every number in the decision doc
uv run export_fixture.py ../../src/test/resources/search-lab/labelled.jsonl.gz   # regression fixture
python3 audit.py labels                                                  # compare against raw/audit/sonnet.jsonl
```

`raw/` is gitignored: it holds peer usernames and 300 MB of responses.

## Query shapes

- **A** bare `title - artist` (the recommended default)
- **B** the YouTube name minus video noise, qualifiers kept (only run when different from A)
- **C** title only (only run when A returned under 20 files; recovers artists the Soulseek server drops)
