# Build on the builder's own CPU: the jar is identical for amd64 and arm64, so only the runtime
# stage is per-architecture and nothing compiles under emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Optional extra root CAs (certs/*.pem, gitignored) for a TLS-intercepting proxy, imported into this
# stage's JDK so the Gradle wrapper and Maven Central downloads verify. keytool keeps only the first
# certificate of a file, hence the split. No .pem files = nothing imported. Build stage only: the
# running app's one HTTPS call of its own is album covers (SongTagger); behind such a proxy they fail
# and songs are filed without one.
COPY certs/ /tmp/certs/
RUN set -e; cd /tmp/certs; \
    for pem in *.pem; do \
      [ -f "$pem" ] || continue; \
      awk -v p="$pem" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (p "-" n ".crt")}' "$pem"; \
      for crt in "$pem"-*.crt; do \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias "extra-$crt" -file "$crt"; \
      done; \
    done

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
# bootJar, not build: build also runs the tests, which need Docker (Testcontainers).
RUN --mount=type=cache,target=/root/.gradle sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source=https://github.com/catacomb5099/naviseerr
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
# Default user; compose's `user: ${PUID}:${PGID}` overrides it. Numeric, so no passwd entry needed.
USER 1000:1000
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS -o /dev/null http://localhost:8080/downloads/active || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
