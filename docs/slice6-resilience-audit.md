# Slice 6, Task 1 — Resilience Audit Log

Run on-device (Pixel 8 Pro) against the full app (Slices 1–5), 2026-07-15.

## 1. Force-kill mid-share

**Method:** `am start` (ACTION_SEND to ShareReceiverActivity) immediately followed by `am force-stop`, timing varied across runs.

- **Bug found:** an item can commit to Room and then have the process killed before `EnrichmentScheduler.enqueue()` runs (the next line in `ShareReceiverActivity.onCreate`). Unlike sync — which has a 15-minute periodic `SyncWorker` safety net that pushes *all* dirty rows regardless of whether a specific enqueue call landed — nothing re-checked orphaned `PENDING` items with no scheduled enrichment job. Confirmed via logcat: `EnrichmentScheduler.enqueue`'s absence leaves the item stuck at `PENDING` forever with zero WorkManager activity.
  - **Fix:** added `ItemDao.getPendingItemIds()` and `EnrichmentScheduler.reconcilePending()`, called from `AnikiApplication.onCreate` on every app start (same self-heal pattern as the Slice-5 FTS backfill). Uses `ExistingWorkPolicy.KEEP` so it never clobbers a job already legitimately in flight/backing off. See [ItemDao.kt](../app/src/main/java/com/aniki/anikiai/data/db/ItemDao.kt), [EnrichmentScheduler.kt](../app/src/main/java/com/aniki/anikiai/work/EnrichmentScheduler.kt), [AnikiApplication.kt](../app/src/main/java/com/aniki/anikiai/AnikiApplication.kt).
- **Known, accepted limitation (not fixed):** an extremely narrow window exists where `am force-stop` (SIGKILL) lands *before* the Room insert transaction itself commits — nothing durable exists yet, so nothing can be recovered. Reproduced once directly (a share fired and killed ~0ms apart never appeared in the DB at all). This is inherent to any architecture where the OS can SIGKILL a process mid-syscall; the write itself is a single fast local Room insert (sub-10ms in practice), and `am force-stop` at this precision is a developer/debugging tool, not a realistic user action. Judged not worth a durable pre-write queue for an MVP given the real-world window is effectively unreachable by normal use.
- Mid-enrichment kill (network call in flight) and mid-sync-push kill: both recovered cleanly on relaunch — item ended up correctly `ENRICHED` / pushed exactly once to Postgres, no duplicates, no corruption.
- Mid-Feed-swipe kill: app relaunched cleanly, no crash (checked logcat for FATAL/AndroidRuntime — none from this app), engagement_events table had no anomalies after.

## 2. Airplane mode (full flow)

**Method:** `svc wifi disable` + `svc data disable` (equivalent to airplane mode from the app's perspective, doesn't require root).

- Shared a link while offline → saved locally, correctly showed "Processing…" in Library (still searchable via local FTS while offline).
- WorkManager jobs for enrichment + sync correctly held in `ENQUEUED` state (`CONNECTED` constraint), not failing/erroring.
- Re-enabled network → enrichment and sync both caught up automatically without any manual retry; item transitioned to "Filed" with real tags, and was confirmed present in Postgres. No manual intervention needed.

## 3. Rapid duplicate shares

**Method:** fired the same URL as a share intent 5× in near-parallel (backgrounded subshells) to stress the dedup check-then-insert path for a TOCTOU race.

- Result: exactly 1 row in the local DB for the URL. No duplicates even under concurrent fire — Room's writer serialization plus the existing `getItemByNormalizedUrl` dedup check held up under this stress test.

## 4. Two-device offline sync conflict (LWW)

**Method:** only one physical device available, so "Device B" was simulated with direct `psql` writes to Postgres (bypassing the app entirely) — a valid stand-in since it exercises the same code path (`SyncManager.runSync` → `decideMerge`) the client would run on a pull.

1. Took device offline (`svc wifi/data disable`).
2. Edited an existing item's summary in the Detail screen while offline (`dirty=1`, local `updatedAt`).
3. Directly `UPDATE`d the same row in Postgres with a different summary, a **later** `updated_at`, and a fresh `seq` (simulating Device B having already synced its own edit while A was offline).
4. Reconnected A's network, relaunched (triggers `SyncWorker.enqueueOneTime` from `MainActivity`).
5. **Result:** correct LWW resolution — the newer (server) edit won, local `dirty` cleared to 0 (no incorrect re-push of the stale local edit), Postgres and the device agree. Verified in the Detail screen (correct summary rendered) and via direct DB query. Feed wasn't separately screenshotted for this specific item (it renders from the same merged row Library does, so it's consistent by construction — already covered by Slice 5's on-device Feed verification), but Library + Detail were both checked directly.

## Cleanup

Synthetic test items created during this audit (2 unresolvable fake-URL articles, 1 test note) were soft-deleted server-side and pulled down as tombstones on next sync, restoring the library to its pre-audit real content. The Kotlin item's summary (mutated for the conflict test) was restored to its original enrichment output.

## Outcome

One real bug found and fixed (orphaned-PENDING reconciliation). Everything else the brief asked to verify held up correctly. The one true data-loss window (kill before the very first Room write commits) is a documented, accepted platform-level limitation rather than a gap in this app's logic.
