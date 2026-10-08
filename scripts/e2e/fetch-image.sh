#!/bin/sh
# `docker pull` hangs behind some Docker Desktop proxies: copy the image with skopeo in a throwaway
# alpine (trusting certs/*.pem if present), then `docker load` it. Usage: fetch-image.sh deluan/navidrome:0.64.2
set -eu
image=$1; out=$(mktemp -d); arch=$(docker version --format '{{.Server.Arch}}')
certs=$(cd "$(dirname "$0")/../.." && pwd)/certs
docker run --rm -v "$out:/out" -v "$certs:/certs:ro" alpine:3.20 sh -c \
  "cat /certs/*.pem >> /etc/ssl/certs/ca-certificates.crt 2>/dev/null || true; apk add -q skopeo && \
   skopeo copy --override-arch $arch --override-os linux docker://docker.io/$image docker-archive:/out/image.tar:$image"
docker load -i "$out/image.tar"
rm "$out/image.tar"; rmdir "$out"
