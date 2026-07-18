# Context for a new Claude Code session

Orientation doc for starting fresh on this project. Read this in full before doing anything else.

## 1. Project overview

Aniki ("big brother" in Japanese) is an Android app that lets users save anything — shared links,
YouTube videos, Instagram posts, notes — via the share sheet with zero friction. An AI pipeline
reads, summarizes, and tags each item. Two surfaces: **Library** (searchable list) and **Feed**
(full-screen, TikTok-style vertical swipe, ranked by relevance, not chronological). Core thesis:
people save things but never revisit them — Aniki fixes retrieval, not storage.

## 2. Tech stack

- **Client**: Android native, Kotlin + Jetpack Compose, Room (+FTS4), WorkManager, Retrofit +
  kotlinx.serialization, Coil3, Firebase Auth (Google Sign-In), DataStore Preferences. AGP 9.2.1
  with built-in Kotlin (no separate `kotlin-android` plugin). minSdk 26, targetSdk 36, compileSdk 37.
- **Backend**: Node.js + TypeScript, Express, raw `pg` (no ORM), `firebase-admin`, `@mozilla/readability`
  + `jsdom` for article extraction, `youtube-transcript` for video captions.
- **LLM**: Google Gemini via `@google/genai` (`gemini-3.5-flash` default, `GEMINI_MODEL` env override).
- **Auth**: Firebase Auth (Google Sign-In client-side; `firebase-admin` verifies ID tokens server-side).
- **DB**: Postgres (local Docker for dev — `docker-compose.yml` at repo root), Room on-device.
- **Dev tooling**: `scripts/aniki-dev-toggle.ps1` + `Aniki Dev Toggle.bat` — one double-click
  starts/stops the backend server + `adb reverse tcp:4000`. Backend tests via Node's built-in
  `node:test` (no framework); Android tests via JUnit (unit) + a small but growing instrumented
  (`androidTest`) suite for anything that needs real SQLite (FTS4, transactions) — see §4.
- **JDK**: no standalone JDK on this machine — use Android Studio's bundled JBR
  (`D:\App Dev\Android Studio\jbr`) as `JAVA_HOME` for `./gradlew`.

## 3. Architecture summary

**Client**: UI (Compose) → ViewModel → Repository (`ItemRepository`, `SyncRepository`) →
Room/Retrofit. Manual DI, no Hilt/Koin — ViewModels built per-screen via `viewModelFactory{}`.
Single-activity nav (`AnikiNavHost`) plus a separate `ShareReceiverActivity` for the share-sheet
target. `ItemRepository` now also holds a reference to `AnikiDatabase` itself (not just `ItemDao`),
used for `withTransaction { }` where a multi-step write needs atomicity — see §4's targeted-write
pattern. Room FTS4 is a standalone virtual table (not `@Fts4(contentEntity=...)`, since `ItemEntity`'s
PK is a UUID string) kept in sync through one choke point, `FtsIndexer`, used by both the local-write
and sync-merge paths. WorkManager: `EnrichmentWorker` (per-item, unique work = itemId),
`SyncWorker` (unique work, periodic 15 min + triggered after most local mutations), plus
`ThumbnailBackfiller`/`NoteTitleBackfiller` which piggyback after every successful sync.

**Backend**: Express, routes for `/enrich` (unauthenticated), `/sync` (Firebase-authenticated),
`/account` (export/delete), `/extract-thumbnail` (unauthenticated, Gemini-free). Raw SQL via `pg`,
hand-rolled migration runner (`server/src/db/migrate.ts`, lexically-sorted `.sql` files in
`server/migrations/`). In-memory content-hash cache, per-IP rate limiter, and metrics counters —
all per-process, not shared across instances (a known limitation if ever scaled horizontally).
`app.set("trust proxy", 1)` is now set (see §5) so the per-IP rate limiter actually sees each
client's real IP behind Render's proxy, not the proxy's own address.

**Sync**: delta sync, row-level LWW keyed on `updatedAt` (conflict clock, client device-millis) vs
`seq` (server-assigned replication cursor — one shared Postgres sequence across all 4 synced
tables, not per-table). Pulls are paginated (page boundary computed via a global-seq-ordered
UNION query so no row is skipped across a page split); the client loops pages but still persists
its cursor exactly once at the end, preserving the original crash-resilience contract. Tombstone
deletes propagate via `deleted_at`, with a daily GC purging tombstones past a retention window.
Every push-side upsert (`upsertItem`/`upsertTag`/`upsertItemTag`/`upsertEngagementEvent` in
`server/src/sync/repo.ts`) is now consistently `user_id`-scoped — `engagement_events` was missing
this until this session (see §5's SEC1).

**Ranking**: deterministic Feed engine (`feed/Ranking.kt`) — recency decay, resurface term, tag
affinity (from engagement history), date-proximity boost, seen-penalty, then a diversity pass.
Feed order is a **frozen per-session snapshot**, not a live Room `Flow` — re-ranking mid-swipe
would be jarring.

## 4. Established conventions and patterns

- **Feed/Library ARE NavHost `composable()` destinations** (`ui/navigation/AnikiNavHost.kt`), using
  the classic `switchTab` `popUpTo(start){saveState=true}` + `launchSingleTop` + `restoreState`
  pattern — restored to this after a brief detour. A `when`-based (non-NavHost) tab model was tried
  mid-project to fix a bottom-bar/content desync, but it broke back-navigation (Library → back used
  to exit the app instead of returning to Feed) and Library's scroll-position retention (NavHost's
  `saveState`/`restoreState` is what preserves that). The actual fix for the desync turned out to be
  simpler: `NavHost(enterTransition = { EnterTransition.None }, exitTransition = ..., popEnterTransition
  = ..., popExitTransition = ...)` — killing the ~700ms default crossfade on *every* destination
  (tabs and pushed screens alike) collapses the bottom-bar-vs-content gap to an imperceptible
  ~1-frame swap, since the original complaint was the crossfade's *duration*, not a structural
  frame-timing issue. If touching tab navigation again: stay on the NavHost model, don't re-introduce
  a parallel `selectedTab`-driven `when`.
- **Overlay/floating bars over full-bleed content must consume their own touches.** Feed renders
  content full-bleed underneath the translucent bottom nav bar by design (the "immersive Feed, bar
  floats on top" look), and Feed's video/article heroes are `Modifier.fillMaxSize().clickable(onOpen)`.
  If a bar composable (e.g. `AnikiBottomBar`'s outer `Box`) only relies on its *children* being
  clickable, a tap landing in the bar's own empty/padding space isn't consumed by anything and falls
  through to whatever's rendered behind it — for the bottom bar this meant tapping empty space
  opened the current Feed item's source URL. Fix: give the bar's own container a no-op
  `clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}` so
  it claims every touch in its bounds regardless of what's underneath. Apply this same instinct to
  any future floating chrome drawn over full-bleed content.
- **Loading vs. confirmed-empty is a real distinction a `StateFlow<List<T>>` alone can't make.**
  `stateIn(..., initialValue = emptyList())` makes "haven't heard from Room yet" indistinguishable
  from "queried and it's genuinely empty" — on a freshly-recreated ViewModel (e.g. Library rebuilt
  after Back off it) this briefly flashed "Nothing saved yet" for a real, non-empty library. Fix
  pattern (see `LibraryViewModel.hasLoadedItems`): a separate `StateFlow<Boolean>` flipped `true` via
  `.onEach { }` on the *upstream* flow (before `stateIn`'s synthetic initial value masks it), and the
  screen renders blank/skeleton instead of the empty-state copy until that flag is true. Reuse this
  pattern for any other list screen that can flash a false-empty state on recreation.
- **Targeted single-purpose column writes, not `getById() → entity.copy() → update(wholeRow)`.**
  `ItemRepository`'s mutation methods used to read the full row, copy it with one field changed, and
  write the *whole* row back — so a concurrent operation landing in that read-write gap had its own
  change silently reverted by the other write's stale copy of that field (worst case:
  `applyEnrichment` copying a stale `titleEditedByUser=false` back over a just-set edit lock,
  defeating it entirely). Fixed session-wide: every single-field mutation (`setStarred`, `deleteItem`,
  `restoreItem`, `updateTitle`, `markNeedsAttention`, `markEnrichmentSkipped`, the tags-edited flag)
  is now a **targeted SQL `UPDATE`** naming only the column(s) it owns — a write that never mentions
  a column cannot clobber it, regardless of interleaving with any other write. Where a write's value
  depends on an edit-lock flag (`applyEnrichment`'s title/summary, `updateNoteBody`'s
  errorCode/errorMessage), the condition is a SQL `CASE WHEN <flag column> = 0 THEN ... ELSE
  <same column> END` evaluated by SQLite against the row's *live* value at write time — never a value
  read earlier in Kotlin. The one remaining read-then-act step that can't be expressed as a single
  UPDATE (`applyEnrichment`'s tags-replacement, gated on `tagsEditedByUser`) is wrapped in
  `AnikiDatabase.withTransaction { }` for atomicity. **Apply this same targeted-write pattern to any
  future `ItemRepository` mutation** — the old whole-row-copy pattern is the exact bug class to avoid
  reintroducing. See `ItemDao.kt`'s "Targeted single-purpose column writes" section for the query set
  and `ItemRepositoryRaceAndroidTest.kt` for the regression tests (14 tests, real Room/SQLite,
  covering each specific clobber scenario as a deterministic ordering, not a timing-dependent race).
- **Self-guarding deletes for anything a background sweep might race against a user action.**
  `purgeExpiredTrash`'s automatic 30-day sweep used to `SELECT` candidate ids, then unconditionally
  `DELETE ... WHERE id = :id` per id — if a restore landed between the SELECT and that DELETE, the
  just-restored item was permanently hard-deleted. Fixed by folding the *same* guard condition into
  the DELETE itself (`hardDeleteExpiredTrashItem`: `DELETE ... WHERE id=:id AND deletedAt IS NOT NULL
  AND deletedAt < :cutoff AND dirty=0`), so a concurrent restore simply makes it match zero rows.
  Apply this pattern to any future "scan candidates, then destroy them one by one" background job.
- **Process-lifetime session state**: state that must survive a ViewModel being recreated across
  Navigation-Compose tab switches, but should reset on cold start only, lives in the ViewModel's
  `companion object` (plain `private var`s), not in instance fields or `SavedStateHandle`. Pattern
  established for the Feed's frozen session order/fingerprint/last-settled-item (`FeedViewModel`'s
  companion) and the swipe-hint first-run flags. Feed/Library ViewModels persist as long as the app
  process lives anyway (NavHost's `saveState`/`restoreState`), so this companion-object belt is
  somewhat redundant with the suspenders — harmless, but the *simpler* fix for "must survive tab
  switches" going forward is likely just an instance field.
- **`SharingStarted.Eagerly` over `WhileSubscribed(5_000)`** for any StateFlow on a ViewModel that's
  known to persist across tab switches (Library/Feed) — `WhileSubscribed` tears the upstream Flow
  chain down after 5s with no subscriber and pays a real, visible cold-restart cost on the next
  subscribe (e.g. Library's 250ms search debounce + a fresh Room query) exactly when the user
  revisits a tab after a short break. `Eagerly` starts once at construction and never stops for the
  ViewModel's lifetime, matching how long the state is actually needed.
- **`LaunchedEffect(counter)` one-shot-event gotcha, and its resolution.** A ViewModel-persisted
  counter used as a `LaunchedEffect` key to trigger a one-shot UI action used to *replay* on every
  composable remount once the counter had ever incremented (a fresh `LaunchedEffect` sees "current
  value" as a brand-new key). The Feed refresh pill hit this for its blur+scroll-to-top animation.
  **Current fix, not just a workaround**: the choreography no longer reacts to a persisted VM
  counter at all — `FeedViewModel.refreshFeed()` is a `suspend fun` the UI calls and awaits directly
  from the pill's `onClick`, sequencing `refreshing=true (blur in) → delay matching the blur-in
  tween → refreshFeed() → animateScrollToPage(0) → refreshing=false (blur out)` as one coroutine
  owned by the composable. This also fixed a second, related bug: the VM used to publish the
  reordered items *before* bumping the trigger the UI reacted to, so the reordered card could
  flash into view unblurred for a frame before the hide animation caught up — now the reorder
  can't reach the pager until the blur has already had its full ramp-in time (the `delay` before
  `refreshFeed()` is called is dead-reckoned to match the blur-in tween's duration exactly). If you
  ever see this same "counter replays on remount" shape elsewhere, prefer this UI-owns-the-sequence
  restructuring over patching the trigger with a "seen" baseline.
- **Edit-lock pattern** for any user-editable AI-generated field: `xEditedByUser` (Room) /
  `xLocked` (DTO + Postgres column). Set `true` on any user edit; `applyEnrichment()` only
  overwrites the field if the flag is `false` (now enforced via the targeted-write SQL CASE pattern
  above, not a Kotlin-side stale read). Wired end-to-end: entity → repository write path →
  `SyncDto` → `SyncRepository.toDto/toEntity` + `itemContentIdentical` → Postgres column →
  `sync/types.ts` → `sync/repo.ts` row mapping → `mergeLogic.ts itemContentEqual`. Currently
  implemented for `summary`, `tags`, and `title`. Follow this exact pattern for any future
  AI-generated, user-editable field.
- **Monogram/domain-derived UI must normalize the host, not use `Uri.host` raw.** `Uri.host` always
  includes whatever subdomain a URL actually has (`www.`, `en.`, `m.`, ...), so deriving a per-site
  letter/color directly from it collapses every `www.*` site to the same tile and mis-derives the
  initial for anything else (`en.wikipedia.org` showed "E", not "W"). `ItemVisuals.kt`'s
  `registrableLabelFor(host)` strips subdomains and the public suffix (handling two-part suffixes
  like `.co.uk`) before either `monogramLetterFor`/`monogramColorsFor` sees it — route any future
  domain-derived display value (favicon lookup, per-site grouping, etc.) through this same helper
  rather than hashing/slicing `Uri.host` directly.
- **Visual identity**: parchment/oxblood/IBM Plex. Fixed palette tokens live in `ui/theme/Color.kt`
  (`Ink`, `Kon`, `Paper`, `Paper2`, `Seal`, `SealDark`, `Matcha`, `Muted` + derived alpha washes) —
  never invent a new hex; derive tints from existing tokens (see `SealTint1-4` for the pattern).
  The 兄 seal stamp motif means "read/filed by Aniki." Feed is the one dark-ground immersive
  screen (`AnikiTheme(darkGround = true)`); everything else is parchment-light.
- **Schema migrations**: Room — bump `version`, add an additive `Migration` object with a comment
  explaining *why*, no destructive fallback. Mirror on Postgres with a new
  `server/migrations/00N_*.sql` file (forward-only, picked up automatically by the migration runner).
  Bundle a client migration + its server counterpart in the same work session/commit when they're
  part of the same feature. Room schema is currently **version 9** — nothing added this session.
- **SSRF guard reuse**: any server-side fetch of a URL influenced by external content (article
  page, OG-image candidate) must go through `security/urlGuard.ts`'s `assertSafeUrl` — rejects
  non-http(s) schemes and private/loopback/link-local-resolving hosts.
- **Local-only vs synced fields**: bookkeeping flags for per-device backfill attempts
  (`thumbnailBackfillAttempted`, `titleBackfillAttempted`, `isDemo`, `demoLandingAnimationShown`,
  `errorCode`/`errorMessage`) are deliberately *not* synced — each device backfills/behaves
  independently, so one device's state doesn't affect another's. `isDemo` (the onboarding demo item)
  additionally gates sync (never pushed) and enrichment (never real-enriched) but *not*
  Library/Feed/search visibility — it's otherwise a normal, user-deletable item.
- **Every synced table's push-side upsert must scope its existence check by `user_id`.**
  `engagement_events` was the one exception until this session (`id`-only lookup) — a same-id
  collision across two different users would leak the other user's `seq` in the ack and silently
  drop the real push. Fixed in `server/src/sync/repo.ts`; if a new synced table is ever added,
  match `upsertItem`/`upsertTag`/`upsertItemTag`'s `WHERE id = $1 AND user_id = $2` shape exactly.
- **Testing**: pure-logic modules stay Compose/DB-free for easy unit testing (`feed/Ranking.kt`,
  `feed/TagWeights.kt`, `feed/PullQuote.kt`, backend `sync/mergeLogic.ts`). Backend tests use
  Node's built-in `node:test` + `mock.module()` for external deps (no Jest/Vitest) — 60 tests,
  all passing. Android unit tests (JVM, no real SQLite) use JUnit. Anything that needs a *real*
  SQLite engine (FTS4 queries, multi-statement transaction atomicity) belongs in
  `app/src/androidTest/` instead — run via `./gradlew :app:connectedDebugAndroidTest` against a
  connected device/emulator, using `Room.inMemoryDatabaseBuilder`. Two suites exist there now:
  `FtsIndexerAndroidTest` (7 tests) and `ItemRepositoryRaceAndroidTest` (14 tests, the
  data-integrity regression suite from this session). Both currently pass (21/21) on the physical
  Pixel 8 Pro.

## 5. Current state / what's done

MVP (6 slices) is complete, functionally and visually. Post-MVP: hardening batch, visual polish
passes, onboarding share-tip, first production deployment, **Slice 1**, **Slice 2**, an enrichment
failure-handling overhaul, a round of Feed/nav bug fixes, and a full non-functional (scalability/
maintainability/robustness/security/performance) code audit with its highest-priority findings
fixed are all done and **committed to `main`** (currently 2 commits ahead of `origin/main` —
`8665f90` and `a6e2ebb` haven't been pushed yet as of this doc's last update; see §7). Everything
below is verified end-to-end on a physical Pixel 8 Pro; deployment pieces are verified against the
live Render service as of their own commit, not re-verified this session.

- **Non-functional + functional hardening** (earlier session): Room indexes, single-flight Gemini
  cache, error middleware, backend test suite from scratch; Gemini daily-cap retry counting fix,
  2mb Express body limit, SSRF guard on `/enrich`, per-IP rate limiting, tombstone GC, paginated
  sync pulls.
- **Visual polish passes** (earlier session): Feed swipe hint (first-run + idle re-trigger), Library
  thumbnails (OG image → monogram → note glyph, lazy backfill), Feed article/note typographic hero
  (pull-quote), Gemini-generated + user-editable note titles, Library/Feed icon and spacing
  cleanup (`ui/theme/Spacing.kt`).
- **Onboarding "how sharing works" step** (earlier session): `RootState.SHARE_TIP` (`ui/AppRoot.kt`)
  fires a real `ACTION_SEND` with hardcoded demo content so Aniki appears as a genuine share-sheet
  target (`share/OnboardingDemoContent.kt`, `ItemEntity.isDemo` gates sync/enrichment but not
  visibility).
- **Production deployment** (earlier session): backend live on Render (`aniki-server`, free tier —
  see §6 for the cold-start tradeoff), all secrets from env vars, Postgres over SSL
  (`DATABASE_SSL`/`pool.ts`/`migrate.ts`). Client `BuildConfig.BASE_URL` switches per build type
  (`app/build.gradle.kts`) — debug hits local/LAN, release hits the deployed URL. Release signing
  via git-ignored `keystore.properties` (template: `keystore.properties.example`). Tester
  distribution via `scripts/distribute-release.ps1` → Firebase App Distribution (`aniki-testers`
  group); install flow documented for non-technical testers in `tester-install-guide.md`. Full
  writeups: `docs/specification.md` (product) and `docs/technical-document.md` (implementation).
- **Slice 1 — note editing, universal direct-edit titles, Trash** (commit `3499627`): notes and
  article/video titles open directly editable with debounced autosave + flush-on-dispose; a
  title-only edit does not re-enrich (see `ItemRepository.updateNoteBody`'s doc). Trash: soft-delete
  reused as the sync tombstone mechanism, exposed via `TrashScreen` with restore/delete-forever/
  empty-trash. "Articles" renamed to "Links" in the UI (display string only).
- **Slice 2 — Feed content, starring, session-stable ordering** (commit `e9fe1a1`): the Feed order
  is a frozen per-session snapshot re-projected (not re-ranked) on every re-entry; the pager resumes
  to the exact card last settled on. Feed note cards show real body text, not the AI summary.
  Starring works from Feed (`StarStamp` stamp/ripple animation) and partitions starred items into
  their own ranked block. The "Feed updated" refresh pill flips via a live fingerprint comparison
  against the frozen snapshot's baseline.
- **Enrichment failure handling overhaul** (commit `164e0c9`): offline-queued state
  (`util/NetworkStatus.observeOnline`) shows a paused indicator instead of a spinner when `PENDING`
  with no connectivity. Real per-error-code failure messages (`util/EnrichmentMessages.kt`) instead
  of one generic string — `errorCode`/`errorMessage` are new local-only `ItemEntity` columns (Room
  migration 8→9, version 9). `QUOTA_EXCEEDED` skips the normal exponential backoff and reschedules
  once for just past UTC midnight (`EnrichmentScheduler.enqueueAfterQuotaReset`). Notes under 5
  chars skip enrichment entirely (`markEnrichmentSkipped`) rather than sending a doomed Gemini call.
- **A round of post-hoc bug fixes** (commits `7932945`, `87c5aa0`, `a5787d4`, `8665f90`), each found
  by the user during manual testing after the above looked done — details of each are folded into
  the relevant §4 convention entries above (nav model, touch-consumption, loading-vs-empty,
  process-scoped-trigger-vs-suspend-fun, monogram normalization). One-line summary of each:
  - Library briefly flashed "Nothing saved yet" for a real library right after Back-then-reenter.
  - Tapping empty space in the bottom nav bar on Feed opened the current item's source URL.
  - The Feed refresh pill's reordered card could flash into view unblurred before the hide
    animation caught up, and (in an earlier pass at the same fix) lost its visible scroll motion —
    both resolved together.
  - Library's monogram tile showed the wrong letter/color for any URL with a subdomain.
- **Full non-functional code audit + highest-priority fixes** (commit `a6e2ebb`), scoped to what's
  new/changed since the earlier hardening pass — trash/soft-delete, directly-editable notes with
  re-enrichment-on-edit, Feed session-state management, enrichment failure-state handling. Findings
  were rated severity/effort like the earlier audit; the four flagged as live data-loss/security
  risks were fixed immediately (the user explicitly treated this as an interrupt over other
  in-progress work):
  - **R2** (root cause, highest priority): `ItemRepository` mutations used to read the whole row,
    copy one field, and write the whole row back — see §4's targeted-write-pattern entry for the
    full fix. This was a **live** risk: editing a note body re-enqueues enrichment while the user is
    typically still on the Detail screen, so a star/title-edit/delete could be silently reverted by
    the enrichment write landing seconds later.
  - **R1** (rapid star/unstar) and **R4** (autosave flush racing a debounce) were both resolved by
    the same targeted-write construction, confirmed via tests rather than patched separately, since
    they shared R2's exact root cause.
  - **R3**: the 30-day trash auto-purge could permanently destroy an item restored in the gap
    between its candidate scan and its delete — see §4's self-guarding-delete entry.
  - **SEC1**: `engagement_events`' sync upsert was missing `user_id` scoping (every other synced
    table had it) — see §4's push-side-upsert entry.
  - **SEC2**: the `/enrich` per-IP rate limiter was keyed on `req.ip` with no `trust proxy` config,
    so behind Render's proxy every request looked like it came from the same IP — the intended
    per-abuser guard was silently a single shared bucket for the entire userbase. Fixed with
    `app.set("trust proxy", 1)`.
  - Verified via 14 new instrumented tests (`ItemRepositoryRaceAndroidTest`, real Room/SQLite on
    the physical Pixel 8 Pro) reproducing each scenario as a deterministic ordering, plus the full
    existing unit + instrumented + server test suites — all green (21/21 instrumented, full unit
    suite, 60/60 server tests).
  - **Remaining audit findings were NOT fixed this pass** (lower priority / bigger effort) — see §6.

## 6. Known open items / pending decisions

- **Remaining code-audit findings, not yet actioned** (full detail was reported in-conversation,
  not written to a separate doc — re-run a similar audit pass if this list is needed again, or ask
  the user if they kept notes):
  - **S1/S4/P2** (the biggest one): `engagement_events` is append-only and never pruned client- or
    server-side — grows unbounded with usage, and `FeedViewModel.computeUserTagWeights()` scans the
    *entire* local mirror on every Feed refresh (now specifically on the critical path of the R2-era
    refresh-pill fix, so refresh latency grows with history size too). Wants a retention/aggregation
    policy plus (ideally) cached/incrementally-updated tag weights.
  - **M2**: the item field list is duplicated in lockstep across ~6 sites (entity, DTO mappers ×2,
    content-equality checks ×2, Postgres schema) with no compile-time link — a missed field in any
    one silently breaks either sync-skip logic or round-trip fidelity. A round-trip test asserting
    every field survives DTO↔entity would catch this cheaply.
  - Smaller items: no index on `deletedAt` despite it gating nearly every hot query (S3); two eager
    full-table Flow collectors re-materialize the whole items+tags join on every write (S2); FTS
    re-indexes redundantly during a multi-tag sync pull (P1); `EnrichmentErrorCode` is duplicated
    client/server with no compile link (M3); Feed's companion-object session state is now
    "redundant with the suspenders" per §4 (M1).
- **Render free tier cold-starts**: the deployed backend spins down after ~15 min idle; first
  request after that takes 30-60s+. Acceptable for now during early tester feedback; revisit
  (paid tier, or a keep-alive ping) if it becomes a real complaint.
- **Backend has no linting and no CI** — `node:test` suite exists and passes, but nothing runs it
  automatically. Migrations against the deployed DB are run manually (`npm run migrate`) since
  Render's Pre-Deploy Command is a paid-tier feature.
- **Backend in-memory state won't survive horizontal scaling** — content cache, rate limiter, and
  metrics are all per-process. Fine for one instance, would need a shared store (Redis/Postgres)
  before running more than one.
- **Direct Share shortcut (sharing-shortcuts) is not implemented.** Deliberate scope cut, not a bug.
- **Manual "delete forever" / "empty trash" offline edge case**: `permanentlyDeleteItem`/`emptyTrash`
  (the *manual*, user-initiated delete-forever path — distinct from the automatic 30-day sweep R3
  fixed) still hard-deletes unconditionally even if unsynced/offline, mitigated with a warning, not
  fully solved. A genuine fix would mean blocking the UI on a live sync round-trip, judged not worth
  trading away the instant-delete feel for. This is intentionally accepted, documented behavior —
  don't conflate it with R3, which was a genuine unintended race on top of this same general area.

## 7. Up next

No new slice/feature has been scoped by the user yet. All work through the data-integrity audit
fixes is committed to local `main`, but **`main` is 2 commits ahead of `origin/main`** (not pushed)
as of this doc's last update — check `git status -sb` / `git log origin/main..HEAD` and confirm with
the user before pushing if picking this up fresh. Ask the user what's next rather than assuming;
don't invent scope — the remaining audit findings in §6 are candidates but not yet prioritized into
a slice.

## 8. Recent context

- **The physical Pixel 8 Pro currently has NO local data** — the debug build was reinstalled fresh
  partway through this session (a signature mismatch from an earlier release-signed install forced
  an uninstall/reinstall) and is sitting on the sign-in screen. Sign in or continue as guest before
  testing anything that needs existing items.
- **USB/adb flakiness observed this session**: the device dropped off `adb devices` entirely at one
  point mid-session with a Windows driver-level symptom (`Get-PnpDevice` showed a second, unbound
  `ACER ADB Interface` entry with status `Unknown` alongside the normal working one) — resolved by
  unplugging/replugging the cable and re-selecting "File transfer" USB mode on the phone, not by
  any adb-side command (`kill-server`/`start-server` alone didn't fix it). Worth trying that
  physical replug step first if the device vanishes from `adb devices` again.
- **This session's git history**, oldest to newest, all on `main`:
  `164e0c9` (Slice 2 polish + enrichment overhaul + Feed nav fixes, one batched commit) →
  `7932945` (Library empty-state flash) → `87c5aa0` (bottom nav touch-passthrough) →
  `a5787d4` (Feed refresh pill race) → `8665f90` (monogram subdomain fix) →
  `a6e2ebb` (data-integrity audit fixes: R1–R4, SEC1, SEC2). The first commit bundled a large
  amount of previously-uncommitted work from an earlier session (see prior git log if the exact
  original authorship split matters) — everything after it is this session's own diagnosis+fix work.
- **Backend dev server + Postgres were NOT running this session** — the server-side fixes (SEC1,
  SEC2) were verified via `npx tsc --noEmit` + the existing mocked `node:test` suite (60/60 pass,
  no live Postgres needed for that suite). If picking up server work that needs a live DB
  (`/sync` end-to-end, `/enrich` against real Gemini), bring up `docker compose up -d` + `npm run
  dev` + `adb reverse tcp:4000 tcp:4000` first, per the usual flow.
- **`ItemRepository`'s constructor signature changed** this session: `ItemRepository(itemDao,
  ftsIndexer, database)` — the third param (`AnikiDatabase`) is new, used for `withTransaction { }`.
  `AnikiApplication.kt`'s single construction site was updated; no test files construct
  `ItemRepository` directly (confirmed via search before making the change), so this was a safe,
  contained addition. If a future test needs to construct one, it now needs a real or in-memory
  `AnikiDatabase`, not just a mocked `ItemDao` — see `ItemRepositoryRaceAndroidTest.kt` for the
  pattern (`Room.inMemoryDatabaseBuilder`).
- A useful debugging technique from an earlier session, still valid: `adb shell dumpsys gfxinfo
  <pkg> reset` then `dumpsys gfxinfo <pkg>` after an interaction gives real per-frame render timing
  — far more reliable than screenshot timing for ruling in/out genuine rendering jank. Similarly,
  `dumpsys jobscheduler` can confirm a WorkManager job's actual scheduled delay without waiting for
  it to fire.
- `CLAUDE.md` exists and points here — no need for the user to manually reference this file.
