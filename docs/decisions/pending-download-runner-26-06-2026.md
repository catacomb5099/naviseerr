# Decisions: pending-download-runner

- **Date:** 26-06-2026
- **Topic:** pending-download-runner

---

## Decision: Correct Testcontainers coordinates for the BOM-managed 2.x line (+ r2dbc adapter)
**Context:** The plan listed Testcontainers 1.x coordinates (`org.testcontainers:junit-jupiter`, `:postgresql`). Spring Boot 4.0.2 manages Testcontainers 2.0.3, which renamed modules and resolved the old names to empty versions. The R2DBC `@ServiceConnection` factory also needs `org.testcontainers.r2dbc.R2DBCDatabaseContainer`.
**Options Considered:**
1. *Pin 1.x versions explicitly* — fights the BOM, risks incompatibility with Spring Boot 4's service-connection factories.
2. *Use BOM-managed 2.x module names + add the r2dbc adapter* — stays version-aligned with Spring Boot; no hardcoded versions.
**Final Choice:** Option 2 — `testcontainers-junit-jupiter`, `testcontainers-postgresql`, `testcontainers-r2dbc` (all versionless, BOM-managed).
**Rationale:** AGENTS guidance says don't invent versions. The BOM already manages 2.0.3. The r2dbc adapter is mandatory for R2DBC `@ServiceConnection`; without it the context fails with ClassNotFoundException.

---

## Decision: Remove the obsolete SearchServiceTest
**Context:** `compileTestJava` failed on a pre-existing, unrelated test that referenced a 3-arg `SearchService` constructor and a `download(String)` method, both removed in commit 7d26002 ("remove download endpoint from searchService"). This blocked running any tests, including the new ones.
**Options Considered:**
1. *Rewrite it to test current search methods* — fabricates new coverage beyond scope; the test's premise (download orchestration) no longer exists.
2. *Delete it* — it exclusively tests removed behavior; nothing is salvageable.
3. *Leave it* — build stays broken; cannot verify the feature.
**Final Choice:** Option 2 — delete it.
**Rationale:** It is dead code left over from an earlier refactor. Removing it completes that refactor and unblocks the suite. Flagged explicitly to the user as a deviation from the plan.

---

## Decision: Run implementation in a worktree without moving the agent root
**Context:** Workflow rules require an isolated worktree. The created branch is local-only; the `move_agent_to_root` MCP tool runs `git fetch origin <branch>` and fails on local-only branches.
**Options Considered:**
1. *move_agent_to_root* — would fail the origin fetch.
2. *Operate in the worktree via absolute paths + git -C* — no agent-root move needed.
**Final Choice:** Option 2.
**Rationale:** Avoids the fetch failure; edits and builds target the worktree explicitly. Active branch metadata was set to the worktree for the UI diff.

---

## Decision: Verify the atomic claim ordering as claim-then-log
**Context:** The user described "print then change status", but true atomic/exactly-once requires claiming first.
**Final Choice:** Claim (single SKIP LOCKED UPDATE ... RETURNING) first, then log only the returned rows.
**Rationale:** Guarantees a row is logged/processed exactly once even with overlapping cycles or multiple instances; confirmed in e2e (already-claimed row never re-logged).
