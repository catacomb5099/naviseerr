# Conversation: minimal-postgres-downloads
- **Date:** 2026-06-26
- **Topic:** Introduce a minimal Postgres-backed downloads table

---

## Summary

The existing in-progress persistence work (introduced in a previous session) had become a large, complex changeset covering a multi-table schema (`songs`, `song_sources`, `downloads`), Flyway versioned migrations, a blocking JDBC driver alongside R2DBC, a `ProviderRef` JSONB hierarchy, and Testcontainers-based tests — all introduced at once. The goal of this session was to stash that work and rebuild persistence from scratch in the smallest learnable step: a single `downloads` table, a reactive insert on the `/download` endpoint, and a `202 Accepted` response.

A key design question arose mid-plan: should the `status` column use a native Postgres `ENUM` type, a plain `TEXT` column, or a `TEXT` column with a `CHECK` constraint? The user chose Option B (`TEXT` + `CHECK`) after an explanation of why the native Postgres enum requires painful R2DBC codec registration.

## Key Actions

1. Stashed the previous persistence work with `git stash push -u` (recoverable).
2. Added `spring-boot-starter-data-r2dbc` and `r2dbc-postgresql` to `build.gradle` — intentionally omitting Flyway, the JDBC driver, and Testcontainers.
3. Added a `postgres:16-alpine` service to `compose.yaml` with env-var-backed config and a healthcheck.
4. Added R2DBC connection config and `spring.sql.init` schema init to `application.yaml`; created `schema.sql` with `CREATE TABLE IF NOT EXISTS downloads (...)`.
5. Created the `com.catacomb5099.naviseerr.download` package containing:
   - `DownloadStatus` enum: `PENDING`, `IN_PROGRESS`, `FAILED`, `SUCCEEDED`
   - `Download` entity (`@Table("downloads")`): `downloadId UUID`, `songName TEXT`, `status`, `createdAt TIMESTAMPTZ`
   - `DownloadRepository extends ReactiveCrudRepository<Download, UUID>`
   - `DownloadService.requestDownload(String)` — builds a `Download` with a fresh UUID and persists via `R2dbcEntityTemplate.insert()` (forcing INSERT, not UPDATE)
   - `DownloadController` — `POST /download/{songName}` → `202 Accepted` with the created row; `400` for blank name; `500` on DB error
6. Removed the old `download()` method and its slskd processor dependencies from `SearchService`.
7. Deleted the now-invalid `SearchServiceTest` and replaced it with a focused `DownloadServiceTest` that mocks `R2dbcEntityTemplate`.
8. Verified end-to-end: `./gradlew test` passed; live `POST /download/Bohemian%20Rhapsody` returned `202 Accepted` with a `PENDING` row in the DB; the `CHECK` constraint correctly rejected an invalid status value.

## Files Changed

- `build.gradle` — added R2DBC dependencies
- `compose.yaml` — added postgres service
- `src/main/resources/application.yaml` — added R2DBC config and sql.init
- `src/main/resources/schema.sql` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadStatus.java` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/download/Download.java` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadRepository.java` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadService.java` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/download/DownloadController.java` — created (new)
- `src/main/java/com/catacomb5099/naviseerr/services/SearchService.java` — removed download orchestration
- `src/test/java/com/catacomb5099/naviseerr/services/SearchServiceTest.java` — deleted (obsolete)
- `src/test/java/com/catacomb5099/naviseerr/download/DownloadServiceTest.java` — created (new)

## Outcome

The app starts, runs `schema.sql` via `spring.sql.init` on startup, and `POST /download/{songName}` inserts a `PENDING` row and returns `202 Accepted`. The `CHECK` constraint enforces valid status values at the DB level. Tests pass with no live DB required (unit test only at this stage).

Intentionally left out of scope: Flyway, Testcontainers, song/song_source tables, provider refs, status transitions, background workers, SSE.
