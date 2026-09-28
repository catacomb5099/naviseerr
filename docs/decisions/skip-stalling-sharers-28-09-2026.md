# Stop waiting on Soulseek sharers that queue forever

**Date:** 28-09-2026
**Status:** Accepted, implemented
**Builds on:** [search-reliability-trial-27-09-2026.md](search-reliability-trial-27-09-2026.md) (the `queued-budget-ms` ten-minute limit)

## The problem, in numbers

Two playlists were downloaded on the evening of 27-09-2026: "Indie Pop" (133 songs, 89 succeeded) and
"2010's indie rock" (70 songs, 51 succeeded). 14 songs ended as "all download sources exhausted".

The next morning slskd still showed **58 transfers from that evening sitting at "Queued, Remotely",
0%**. About 40 belonged to one sharer (SKYLiGHT_B) and about 10 to another (musicmasterrdjpool). Both
accept every request and then never send a byte.

What that cost:

- Every one of those 58 requests used up the full ten-minute queued budget before the song moved on to
  its next candidate. SKYLiGHT_B alone stalled **23 songs that later succeeded** from another sharer --
  each of them ten minutes late for nothing.
- **12 of the 14 songs that ran out of sources** had only those two sharers as candidates.
- Songs whose first source worked finished on average 22 minutes after the playlist started; one
  failover pushed that to 33 minutes, two to 36, three to 43.
- The abandoned requests were never cancelled, so they kept our place in the sharer's queue and stayed
  in slskd's list.

Two smaller wastes showed up in the same data: 8 transfers were refused with "File not shared" (the
sharer's index is stale) and we asked the same sharer for the same file again, twice; 2 were refused
with "Overwhelmed with requests".

## What changes

1. **A sharer that stalls one song is skipped by every song.** When a song gives up on a sharer after
   the ten-minute queued budget, that sharer's name goes on a "stalling" list for six hours
   (`download-task.stalling-sharer-cooldown-ms`, default 21600000). While it is there, no song starts
   on that sharer's file if it has any other sharer's file to try. If every remaining candidate is a
   stalling sharer, they are still tried -- a slow success is better than a failure. One INFO log line
   when a sharer enters the list.
2. **The abandoned request is cancelled in slskd** (`DELETE /transfers/downloads/{sharer}/{id}`). Fire
   and forget: the song has already moved on and is written either way; if slskd says the transfer is
   already gone, that counts as done; anything else is a WARN. The same cancel fires when the hour-long
   download budget runs out on a transfer slskd still has running.
3. **A refusal goes straight to the next sharer.** A transfer slskd reports as "Completed, Rejected"
   skips the same-file retries and moves to the next candidate. Asking again for a file the sharer
   says it does not have gets the same answer.
4. **Candidates remember what the sharer advertised**, and the worst profile is ranked last. Each
   stored candidate now carries the sharer's free-slot flag, queue length and upload speed, so the
   database can answer "why was this sharer picked?" after the fact. A sharer with **no free slot and
   more than 50 files already queued** (`download-task.max-sharer-queue`) is ranked behind every other
   candidate, whatever its file's length.

Not done: a cap on simultaneous requests to one sharer (the "Overwhelmed with requests" case, 2 of ~200
transfers). A per-pass cap in the runner would be toothless -- the next pass two seconds later sends
more -- and a real cap needs a per-sharer count of running transfers in the claim query. Left for a
follow-up if it recurs.

## Trade-offs

**The stalling list is in memory.** It is lost on a restart and each naviseerr process keeps its own.
That is accepted: the cooldown is hours, restarts are rare, and the cost of forgetting is one more
ten-minute lesson per sharer. A `stalling_sharers` table is the upgrade if it ever needs to survive a
restart or be shared -- the list is behind one small class (`StallingSharers`), so nothing else would
change.

**Six hours, not a day or forever.** The two measured sharers never served a single file all evening,
so anything shorter than the evening would have re-learned the lesson mid-playlist. But a sharer that
was merely offline or saturated one night is worth asking again the next day, and a permanent blocklist
would slowly shrink the pool of sources with no way back. Six hours covers a long session and clears
by morning. Configurable for people who disagree.

**Skipped candidates are not revisited.** If a song's list is [A (stalling), B, C], it starts on B; if
B fails and only C remains it tries C, never A. A sharer that stalled another song within the last
few hours is the worst bet in the list, not a fallback worth keeping.

**The duration vote stays ahead of availability -- except for the overloaded profile.** Ranking by the
most-shared file length was measured at 80% -> 93% correct top pick on 718 songs; it is what stops a
remix or live take being downloaded as the song. Putting general "busy-ness" ahead of it would trade
correctness for speed on every song. The one exception is a sharer with no free slot and a queue over
the threshold, which is the profile of a sharer that will not reach us inside the ten-minute budget
at all; that one goes last across the whole list. Putting the demotion inside each length group
instead would change nothing, because the existing availability order already puts busy sharers at
the end of their group. The threshold is high on purpose: 50 waiting files is not "busy", it is "not
tonight". The price is that when *every* sharer of the majority length is that overloaded, an
odd-length file is tried first -- and with item 1 in place that song would have reached the same file
ten minutes later anyway.

**No schema migration.** Candidates are stored as a JSON array in a text column, so the three new
fields are read back as null on old rows and written on new ones.

## Where to look

- `download/StallingSharers.java` -- the list.
- `download/DownloadStateMachine.java` -- `afterDownloadPoll` records the sharer and treats a rejection
  as "next candidate"; `pickCandidate` does the skipping for both the first pick and failover.
- `download/DownloadStepExecutor.java` -- `cancelIfAbandoned`.
- `services/slskd/SlskdService.java` -- `cancelDownload`.
- `services/slskd/SlskdSearchResultProcessor.java` -- `isOverloaded` and the comparator.
- `download/DownloadCandidate.java` -- the three new fields.
