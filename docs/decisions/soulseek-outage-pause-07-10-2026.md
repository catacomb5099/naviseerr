# Downloads wait out a Soulseek outage instead of failing (07-10-2026)

## The problem

When the internet drops, slskd stays up but loses Soulseek: `GET /server` says `isLoggedIn=false` and `POST /searches` answers 409. Every budget in the download loop is wall-clock from `phase_entered_at` and every retry counter is small, so an outage of more than one or two minutes turned every song that was searching or waiting for a transfer into a red card (`SOULSEEK_OFFLINE`, `NO_CANDIDATES`, `SOURCES_EXHAUSTED`) the user had to retry by hand. The loop itself never died; the songs did.

## The decision

- Every pass asks slskd `GET /server` first (the idle keep-alive already made this call; it is now unconditional and the keep-alive is gone).
- While `isLoggedIn` is false: claim nothing, step nothing, and run one statement (`DownloadTaskRepository.pauseDueWork`) that moves `phase_entered_at` and `next_attempt_at` of every unfinished `download_tasks` and `album_searches` row forward by the time since the previous held pass (the first by one loop interval; never less than the interval). Overdue rows become due one interval from now; rows due later keep their distance. Conclusion and filing still run.
- Log the transitions only: one INFO when Soulseek goes, one when it is back. Nothing per pass.
- Ceiling: `DownloadTaskRunner.OFFLINE_PAUSE_CEILING`, 30 minutes, a constant. Past it, one WARN and the pass steps as before, so the songs fail as `SOULSEEK_OFFLINE` (PR #97's 409 handling) and a wrong Soulseek username or password still ends in a card that says why. Without the ceiling a wrong password would read "Searching..." for ever.

## Why this shape

- No schema change: an UPDATE, no new column. Both tables' due indexes cover the WHERE.
- Holding the clocks is what makes the budgets honest: "two minutes of searching" should mean two minutes in which searching was possible.
- Shifting by the measured time between held passes, not by the loop interval: passes are not always the interval apart. Admission runs before the gate and, with the internet down, waits on ytmusic-adapter for every PENDING download (15 s timeout, two retries: up to ~45 s per pass), and `Flux.interval` drops the ticks missed meanwhile. Shifting by 2 s per 45 s pass would have let the budgets run down at nearly real speed during the very outage this is for (review, 07-10-2026).
- slskd itself being unreachable (container restarting, wrong URL) is left as it was, bar the logging and the rest of the pass surviving (the fix PR under this one): that is not "the internet cut out", and the ceiling logic would need a second clock. Flip: `.onErrorReturn(false)` on the `GET /server` would treat it as offline too.

## Known ceilings (stated, not fixed)

- A transfer slskd marked Errored during the outage still costs one of its two same-candidate retries when stepping resumes.
- A search slskd completed empty during the outage still advances a wording.
- The pause is process-wide: slskd flapping every pass would hold and release every pass; the transition-only logging keeps that quiet and the held clocks make it harmless.
- With hundreds of unfinished rows the UPDATE touches all of them every two seconds while offline; still one statement, both WHEREs indexed.

## Follow-up (not in this PR)

A `soulseekConnected` field on `GET /downloads/active`, read from the same `GET /server`, so the web app's download panel can say "Downloads paused: Soulseek offline".
