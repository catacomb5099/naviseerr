# naviseerr's setup step (setup.sh) as an image, so an install from ready-made images needs no copy
# of this repo. Built by .github/workflows/image.yml as ghcr.io/catacomb5099/naviseerr-setup, with
# docker/ as the build context. Runs as root on purpose: it gives the shared folders to PUID:PGID.
FROM alpine:3.20
COPY setup.sh /setup.sh
LABEL org.opencontainers.image.licenses=AGPL-3.0-only
ENTRYPOINT ["sh", "/setup.sh"]
