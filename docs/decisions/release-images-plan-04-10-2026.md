# Publishing ready-made images: the plan

**Date:** 04-10-2026
**Status:** Proposed. A plan only: no image is published, no workflow file is committed, no setting is
changed.
**Builds on:** [all-in-one-install-30-09-2026.md](all-in-one-install-30-09-2026.md), whose follow-up list
says "Published images on GHCR, so the first start downloads instead of building".

Five terms, explained once:

- **Image:** one part of naviseerr (the server, the web app, the YouTube Music helper, the curator),
  packed ready to run. Docker starts a container from an image.
- **Registry:** the website images are downloaded from. Think of it as an app store for images.
- **Tag:** the label after the colon, as in `naviseerr:0.1.0`. It says which version you get.
- **Multi-arch:** one image name that works on both kinds of processor found in home servers: Intel/AMD
  PCs (amd64), and Raspberry Pis and many NAS boxes (arm64).
- **CI:** GitHub's own machines building the images every time code lands (GitHub Actions).

## 1. The recommendation in five lines

1. **Where:** GitHub's registry, `ghcr.io/catacomb5099/<name>`, public, next to the code. No new account
   and no stored password.
2. **Tags:** `edge` is the newest move-fast-break-things build. A release publishes `0.1.0`, `0.1` and
   `latest`. Every build also gets `sha-1a2b3c4`, after the commit it was built from.
3. **Trigger:** every merge publishes `edge`. A GitHub Release `v0.1.0`, with the same number in all four
   repos, publishes that version. Pull requests build but never publish. GitHub builds on real amd64 and
   real arm64 machines.
4. **Users:** `compose.yaml` downloads the exact version it was released with. Update:
   `git pull && docker compose pull && docker compose up -d`. Go back: put the previous number in `.env`,
   and restore a database backup if that release changed the database.
5. **Cost:** $0. Public images and GitHub's build machines are free for public repos. About an hour to
   set up, then about ten minutes per release.

## 2. Today vs after

| | Today | After |
|---|---|---|
| Install | `git clone -b move-fast-break-things …`, `docker compose up -d` | `git clone …`, `docker compose up -d` |
| First start | builds four images from source: minutes on a PC, far longer on a Raspberry Pi | downloads about 225 MB (server 145, adapter 57, web app 23; curator +52): a minute or two |
| What you get | whatever the branches hold that minute | a numbered version, the same for everyone |
| Update | `git pull && docker compose up -d --build` | `git pull && docker compose pull && docker compose up -d` |
| Go back | impossible | the previous number in `.env` |

What breaks today and goes away:

- **Small machines.** Every install compiles Java, installs Node and Python packages and builds the web
  app on the user's device: slow on a Pi or small NAS, and it can run out of memory. NAS app catalogs list
  apps by image name, so naviseerr cannot be listed there.
- **Builds from GitHub URLs follow moving branches.** Two people installing a day apart get different
  code, half-finished work included, and every install needs npm, PyPI and Maven Central up at that moment.
- **The laptop certificate.** Behind a proxy that intercepts HTTPS (the owner's laptop) a build needs the
  proxy's certificate in the gitignored `certs/` folder, which a GitHub-URL build never has (the 30-09
  failure). CI needs none. Checked: a fresh checkout builds because each repo tracks a `certs/README.md`
  placeholder; with no `certs/` folder the build fails (`"/certs": not found`). Keep the placeholders.

Installing still starts with `git clone`: `compose.yaml` needs `docker/setup.sh` from the checkout.

## 3. Decisions

Recommended choice in **bold**.

1. **Registry: GHCR** / Docker Hub / both. GHCR: free for public images, no extra account, publishes
   with the run's temporary token, shown on the repo page. Docker Hub is easier to find but needs a second
   account and a long-lived token in four repos, and its limits (100 downloads per 6 hours without an
   account) count against users. Adding it later is two lines and one secret.
2. **Names: `naviseerr`, `naviseerr-client`, `naviseerr-ytmusic-adapter`, `croissant`.** Renaming later
   breaks every user's compose file. Not `ytmusicapi`: that is the upstream library's name.
3. **Versioning: one stack version for all four repos** / one per repo. Each release tags all four with
   the same number, so users track one `NAVISEERR_VERSION` (as Immich does with `IMMICH_VERSION`). Start at
   `0.1.0`. Last number: fixes. Middle number: features **and any database change**, so going back within
   0.1.x is always safe. Release by hand (`gh release create`, one per repo). release-please cannot keep
   four repos on one number, and tags it makes with the workflow token do not start a build.
4. **Tags: `edge` from move-fast-break-things (croissant: `main`); `X.Y.Z`, `X.Y`, `latest` from a
   `vX.Y.Z` release; `sha-<7>` on every build.** `compose.yaml` pins the exact `X.Y.Z`, so nobody is
   upgraded until they choose. Seerr, Navidrome and Immich do the same (their `develop` is our `edge`).
5. **Branches: work keeps landing on move-fast-break-things; `master` is fast-forwarded to each release**
   (naviseerr only). Plain `git clone` then gets the latest release, `git pull` updates it, and GitHub's
   front page stops showing a README 58 commits old. `master` is an ancestor of move-fast-break-things, so
   this never rewrites history. Testers clone move-fast-break-things and set `NAVISEERR_VERSION=edge`.
6. **arm64: GitHub's own arm64 machines (`ubuntu-24.04-arm`, free for public repos)** / emulation (QEMU).
   Emulation makes `npm ci` and `pip install` several times slower. 32-bit Arm waits until someone asks.
7. **Build workflow: Docker's ready-made `docker/github-builder` (v1.17.0)** / ~80 lines of our own per
   repo. Docker's is ~30 lines, builds each processor on its own machine, joins them under one name and
   signs. It sees a token that can publish; it is Docker's own, pinned to `@v1`.
8. **Signing: the default signed build record ("provenance")**, signed with GitHub's identity, so no key
   to lose. It proves which repo and commit built an image. GitHub's "artifact attestations" would repeat it.
9. **SBOM (list of what is inside an image): on.** One line; lets security scanners check the image.
10. **Who publishes: only each repo's workflow, on a merge or a `v*` tag, with the run's temporary token.**
    No stored tokens; pull requests never publish; never from a laptop (see Risks).
11. **Update notices: GitHub "Watch → Custom → Releases"**, or Diun for those who run it. No auto-updater:
    Watchtower is archived (17-12-2025), and an exact pin never moves, on purpose: database changes only
    go forward.

## 4. Rollout

One PR or one click per step. A Claude session writes the PRs on request; you merge and check. Commands
are in Appendix C.

1. Merge this plan.
2. **Adapter first** (smallest build): merge the PR adding `.github/workflows/images.yml` (Appendix A).
   *Undo: delete the file.*
3. Check: run green; package page shows `edge`, `sha-…`, two platforms, linked to ytmusicapi.
4. Make the package public. **The one step that cannot be undone**: it can never be private again (it can
   be deleted while every version has under 5,000 downloads). Test a download while logged out.
5. Repeat 2–4 for naviseerr-client, then naviseerr, then croissant (from `main`).
6. Choose a licence (see Risks).
7. A PR switches `compose.yaml` to `image: ghcr.io/catacomb5099/<name>:${NAVISEERR_VERSION:-0.1.0}`, adds
   `NAVISEERR_VERSION` to `.env.example` and updates the README (Appendix B). `compose.dev.yaml` keeps
   building from source. Test the branch on the home server with `NAVISEERR_VERSION=edge`; don't merge yet.
8. Release `v0.1.0` of the adapter, the web app and croissant; wait for the three runs.
9. Merge step 7's PR, then release naviseerr `v0.1.0`. Its image follows about five minutes later.
10. Fresh install on a second machine, ideally arm64; time it.
11. Fast-forward `master` to `v0.1.0`. *Undo needs a force push; ship 0.1.1 instead.*
12. Existing installs (yours): `git fetch && git switch master && docker compose up -d`. Same project
    name, same volumes: the database and Soulseek secrets carry over.
13. Later releases: Appendix D, about ten minutes.

**Going back (users):** `NAVISEERR_VERSION=<previous>` in `.env`, then `docker compose up -d`. The database
only moves forward (Flyway migrations): within one middle number nothing to do; across one, restore the
backup taken before upgrading (Appendix C).

## 5. Risks

- **A broken release.** Nobody gets it until they `git pull`. Fix forward with 0.1.1; never reuse a
  number. A broken `edge` hits only testers; pull request builds catch a broken Dockerfile first.
- **Secrets.** The only credential is the run's temporary token, hidden in logs. `.env` and `certs/*.pem`
  are gitignored, so never in CI. Build records list build arguments publicly: never pass a secret as one.
  **Never publish from the laptop**: the adapter copies `certs/*.pem` into its image, so the corporate
  proxy certificate would ship to every user.
- **Package left private.** Users see "denied". Step 4's logged-out test catches it.
- **Size.** The server is a 145 MB download, mostly Java. Fine; slim it only if Pi users complain.
- **Cost.** Actions and GHCR are free while the repos are public.
- **Base-image security fixes** arrive only when images rebuild (every merge and release). Add a monthly
  rebuild if that proves too rare.
- **No licence.** None of the four repos has one, so legally nobody else may run or share the code.
  Owner's call before the first release (MIT: anything goes; AGPL: changes stay open).
- **Legal neutrality.** Describe what naviseerr does, not what it gets you. Package pages show the repo
  descriptions, neutral today. Reword the README's first line ("free sources … torrent indexers") in step
  7. The images hold no music and no Soulseek client (slskd comes from its own image).
- **"unknown/unknown" platforms** on the package page are the build records, not a broken image.

---

## Appendix A. Draft workflows (DRAFT, not committed as a workflow)

Each block would become `.github/workflows/images.yml` in that repo. The four differ only in the image
name and the trunk branch. `type=edge` needs the branch spelled out: by default it follows the repo's
default branch (`master`/`main`), and move-fast-break-things is not the default branch. The jobs inside
`docker/github-builder` declare no permissions of their own, so they use the ones granted here.

**ytmusicapi (the adapter)**

```yaml
# DRAFT - not committed as a workflow
name: images
on:
  push:
    branches: [move-fast-break-things]
    tags: ['v*.*.*']
  pull_request:
    branches: [move-fast-break-things]
  workflow_dispatch:   # "Run workflow" button: rebuild a branch or a tag by hand
permissions:
  contents: read
jobs:
  image:
    uses: docker/github-builder/.github/workflows/build.yml@v1
    permissions:
      contents: read
      id-token: write   # signs the build record with GitHub's identity
      packages: write   # publishes to ghcr.io with this run's token
    with:
      output: image
      push: ${{ github.event_name != 'pull_request' }}
      platforms: linux/amd64,linux/arm64   # arm64 runs on ubuntu-24.04-arm, no emulation
      sbom: true
      cache: true
      set-meta-labels: true        # source repo, commit, version, description
      set-meta-annotations: true   # description on the package page
      meta-images: ghcr.io/catacomb5099/naviseerr-ytmusic-adapter
      meta-tags: |
        type=edge,branch=move-fast-break-things
        type=semver,pattern={{version}}
        type=semver,pattern={{major}}.{{minor}}
        type=sha
      # `latest` is added on version tags by default (flavor latest=auto)
    secrets:
      registry-auths: |
        - registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
```

**naviseerr-client (the web app):** the same file with
`meta-images: ghcr.io/catacomb5099/naviseerr-client`.

**naviseerr (the server):** the same file with `meta-images: ghcr.io/catacomb5099/naviseerr`. Its
Dockerfile already sets the `org.opencontainers.image.source` label and compiles on the build machine's own
processor.

**croissant (the curator):** the same file with `main` in place of `move-fast-break-things` in all three
places (`branches` twice, `type=edge,branch=main`) and `meta-images: ghcr.io/catacomb5099/croissant`.

Optional, not needed with native arm64 machines: adding `--platform=$BUILDPLATFORM` to the web app's Node
build stage would make it build once instead of once per processor, since its output is plain files.

## Appendix B. The compose.yaml change

```diff
   ytmusic-adapter:
-    build: https://github.com/catacomb5099/ytmusicapi.git#move-fast-break-things
+    image: ghcr.io/catacomb5099/naviseerr-ytmusic-adapter:${NAVISEERR_VERSION:-0.1.0}
@@
   naviseerr:
-    build: .
+    image: ghcr.io/catacomb5099/naviseerr:${NAVISEERR_VERSION:-0.1.0}
@@
   client:
-    build: https://github.com/catacomb5099/naviseerr-client.git#move-fast-break-things
+    image: ghcr.io/catacomb5099/naviseerr-client:${NAVISEERR_VERSION:-0.1.0}
@@
   croissant:
-    build: https://github.com/catacomb5099/croissant.git#main
+    image: ghcr.io/catacomb5099/croissant:${NAVISEERR_VERSION:-0.1.0}
```

The comments that say "Built from GitHub" are updated in the same PR. `compose.dev.yaml` does not change.
An empty `NAVISEERR_VERSION=` in `.env` means "use the default" (`:-`). Checked on a copy of today's
`compose.yaml`: `docker compose config --images` lists the four `:0.1.0` images with the variable empty,
and `:edge` with `NAVISEERR_VERSION=edge`. In `.env.example`:

```sh
# Optional. Which naviseerr version to run. Leave empty for the version this folder was released
# with. `edge` is the newest work in progress and may break. To go back after an upgrade, put the
# previous version here, but read "Going back" in the README first.
NAVISEERR_VERSION=
```

README: Install step 1 becomes a plain `git clone https://github.com/catacomb5099/naviseerr.git`, and
Install step 3 says the first start downloads about 225 MB. Updating becomes `git pull && docker compose pull && docker compose up -d`.
A new "Going back" section covers the backup commands below, and a line says "Watch → Custom → Releases to
hear about new versions".

## Appendix C. Owner commands

Run by you. On the work laptop, put `~/.gh-catacombs/ghc` in front of every `gh` (the plain `gh` there is
signed in to the work account).

```sh
# Rollout steps 2-3, after merging a workflow PR (repo: ytmusicapi, naviseerr-client, naviseerr, croissant)
gh run list --repo catacomb5099/ytmusicapi --workflow images.yml --limit 3
```

Rollout step 4 happens in the web UI:
`https://github.com/users/catacomb5099/packages/container/package/naviseerr-ytmusic-adapter` → Package
settings → Danger Zone → Change visibility → Public → type the name. Then check while logged out: the
page should open in a private browser window, and

```sh
docker logout ghcr.io
docker buildx imagetools inspect ghcr.io/catacomb5099/naviseerr-ytmusic-adapter:edge
# lists linux/amd64 and linux/arm64
```

Rollout steps 8–9, the first release. Each command creates the tag and a release page listing the merged PRs:

```sh
gh release create v0.1.0 --repo catacomb5099/ytmusicapi      --target move-fast-break-things --generate-notes
gh release create v0.1.0 --repo catacomb5099/naviseerr-client --target move-fast-break-things --generate-notes
gh release create v0.1.0 --repo catacomb5099/croissant        --target main --generate-notes
# once those three runs are green and step 7's PR is merged:
gh release create v0.1.0 --repo catacomb5099/naviseerr        --target move-fast-break-things --generate-notes
```

If no run starts within a minute of a release, start it by hand on the tag:
`gh workflow run images.yml --repo catacomb5099/<repo> --ref v0.1.0`. The same command rebuilds any
version later, for example to pick up a security fix in a base image.

Rollout step 10, a fresh install on a second machine. Use a Soulseek username that is not already logged in
anywhere:

```sh
git clone -b v0.1.0 https://github.com/catacomb5099/naviseerr.git && cd naviseerr
cp .env.example .env          # fill in SOULSEEK_USERNAME
time docker compose up -d
```

Rollout step 11, moving `master`. It refuses (error 422) instead of overwriting if `master` is not behind the tag:

```sh
sha=$(gh api repos/catacomb5099/naviseerr/commits/v0.1.0 --jq .sha)
gh api -X PATCH repos/catacomb5099/naviseerr/git/refs/heads/master -f sha="$sha" -F force=false
```

Database backup before an upgrade, and going back across a database change. If you changed
`POSTGRES_USER` or `POSTGRES_DB` in `.env`, use those names instead:

```sh
docker compose exec -T postgres pg_dump -U naviseerr naviseerr > naviseerr-backup-0.1.0.sql

docker compose stop naviseerr
docker compose exec -T postgres dropdb -U naviseerr naviseerr
docker compose exec -T postgres createdb -U naviseerr naviseerr
docker compose exec -T postgres psql -q -U naviseerr naviseerr < naviseerr-backup-0.1.0.sql
# NAVISEERR_VERSION=0.1.0 in .env, then
docker compose up -d
```

## Appendix D. Every later release (about ten minutes)

0. Run `edge` on the home server for a day first.
1. Check for database changes since the last release (put its number in place of `v0.1.0`). If anything
   is listed, raise the middle number and start the release notes with "Changes the database: back up
   first".
   ```sh
   gh api repos/catacomb5099/naviseerr/compare/v0.1.0...move-fast-break-things --jq '.files[].filename' | grep db/migration
   ```
2. Ask Claude for a "release X.Y.Z" PR in naviseerr. It changes the four `:-0.1.0` defaults in
   `compose.yaml`.
3. Release the adapter, the web app and croissant (Appendix C), and wait for the runs to go green.
4. Merge the release PR, release naviseerr, and wait for its run to go green.
5. Fast-forward `master` (Appendix C, rollout step 11).

## Appendix E. Sources (checked 04-10-2026)

- GHCR is free for public packages, and container storage and bandwidth are "currently free":
  https://docs.github.com/en/billing/concepts/product-billing/github-packages
- New packages start private. Publishing with `GITHUB_TOKEN` links the package to the workflow's repo, as
  does the `org.opencontainers.image.source` label. A multi-arch description is a manifest annotation:
  https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry
- "Once you make a package public, you cannot make it private again":
  https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility
- Delete only under 5,000 downloads per version; restore within 30 days:
  https://docs.github.com/en/packages/learn-github-packages/deleting-and-restoring-a-package
- Standard runners, `ubuntu-24.04-arm` included, are "free and unlimited on public repositories":
  https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- docker/github-builder (v1.17.0, 21-08-2026): its default runner sends arm64 to `ubuntu-24.04-arm`, it
  signs the build record when pushing, and it has `sbom` and `cache` inputs:
  https://github.com/docker/github-builder. It is the approach Docker's docs recommend for splitting
  platforms across runners: https://docs.docker.com/build/ci/github-actions/multi-platform/. Current majors of
  the actions it wraps: build-push-action v7, metadata-action v6, login-action v4, setup-buildx-action v4.
- Tag rules (`type=edge` follows the default branch unless given `branch=`; prereleases only get
  `{{version}}`; `latest=auto` on version tags): https://github.com/docker/metadata-action
- Docker Hub limits: 100 pulls per 6 hours without an account, 200 with a free one:
  https://docs.docker.com/docker-hub/usage/pulls/
- Artifact attestations (actions/attest-build-provenance v4 wraps actions/attest), free on public repos:
  https://github.com/actions/attest-build-provenance
- release-please-action v5.0.0 (22-04-2026): https://github.com/googleapis/release-please-action. Events
  triggered by `GITHUB_TOKEN` do not start new workflow runs:
  https://docs.github.com/en/actions/concepts/security/github_token
- Watchtower archived 17-12-2025: https://github.com/containrrr/watchtower. Diun only notifies:
  https://crazymax.dev/diun/
- Immich: `image: ghcr.io/immich-app/immich-server:${IMMICH_VERSION:-release}`, `IMMICH_VERSION=v3` in
  `example.env`: https://github.com/immich-app/immich/tree/main/docker
- Seerr (Jellyseerr's successor) tags `latest`, `v3.0.0`, `v3.0`, `v3` and `develop` on `ghcr.io/seerr-team/seerr`:
  https://docs.seerr.dev/getting-started/docker/
- Navidrome: `deluan/navidrome:latest`, plus version, `develop` and `pr-N` tags on Docker Hub:
  https://www.navidrome.org/docs/installation/docker/
- Measured on 04-10-2026 on the author's laptop (arm64 images built 01-10, `docker save | gzip -1`): server
  145 MB, adapter 57 MB, web app 23 MB, croissant 52 MB. Measured at the same time: a `COPY certs/` into a
  folder that holds only a README builds, and a missing folder fails.
- Repo facts at 04-10-2026: all four repos are public; none has a licence or a `.github` folder; Actions is
  enabled and the workflow token is read-only by default (the drafts ask for `packages: write`
  themselves); naviseerr's `master` is 58 commits behind and an ancestor of move-fast-break-things.
