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
  `node:test` (no framework); Android tests via JUnit + MockK.
- **JDK**: no standalone JDK on this machine — use Android Studio's bundled JBR
  (`D:\App Dev\Android Studio\jbr`) as `JAVA_HOME` for `./gradlew`.

## 3. Architecture summary

**Client**: UI (Compose) → ViewModel → Repository (`ItemRepository`, `SyncRepository`) →
Room/Retrofit. Manual DI, no Hilt/Koin — ViewModels built per-screen via `viewModelFactory{}`.
Single-activity nav (`AnikiNavHost`) plus a separate `ShareReceiverActivity` for the share-sheet
target. Room FTS4 is a standalone virtual table (not `@Fts4(contentEntity=...)`, since `ItemEntity`'s
PK is a UUID string) kept in sync through one choke point, `FtsIndexer`, used by both the local-write
and sync-merge paths. WorkManager: `EnrichmentWorker` (per-item, unique work = itemId),
`SyncWorker` (unique work, periodic 15 min + triggered after most local mutations), plus
`ThumbnailBackfiller`/`NoteTitleBackfiller` which piggyback after every successful sync.

**Backend**: Express, routes for `/enrich` (unauthenticated), `/sync` (Firebase-authenticated),
`/account` (export/delete), `/extract-thumbnail` (unauthenticated, Gemini-free). Raw SQL via `pg`,
hand-rolled migration runner (`server/src/db/migrate.ts`, lexically-sorted `.sql` files in
`server/migrations/`). In-memory content-hash cache, per-IP rate limiter, and metrics counters —
all per-process, not shared across instances (a known limitation if ever scaled horizontally).

**Sync**: delta sync, row-level LWW keyed on `updatedAt` (conflict clock, client device-millis) vs
`seq` (server-assigned replication cursor — one shared Postgres sequence across all 4 synced
tables, not per-table). Pulls are paginated (page boundary computed via a global-seq-ordered
UNION query so no row is skipped across a page split); the client loops pages but still persists
its cursor exactly once at the end, preserving the original crash-resilience contract. Tombstone
deletes propagate via `deleted_at`, with a daily GC purging tombstones past a retention window.

**Ranking**: deterministic Feed engine (`feed/Ranking.kt`) — recency decay, resurface term, tag
affinity (from engagement history), date-proximity boost, seen-penalty, then a diversity pass.
Feed order is a **frozen per-session snapshot**, not a live Room `Flow` — re-ranking mid-swipe
would be jarring.

## 4. Established conventions and patterns

- **Process-lifetime session state**: state that must survive a ViewModel being recreated across
  Navigation-Compose tab switches, but should reset on cold start only, lives in the ViewModel's
  `companion object` (plain `private var`s), not in instance fields or `SavedStateHandle`. Pattern
  established for the Feed's frozen session order/fingerprint/last-settled-item (`FeedViewModel`'s
  companion) and the swipe-hint first-run flags. Note: as of the nav restructure below, Feed/Library
  ViewModels turned out to already persist as long as the app process lives (see next point), so this
  companion-object belt is now redundant with the suspenders — harmless, but if touching this again,
  the *simpler* fix for "must survive tab switches" is likely just an instance field.
- **Feed/Library are NOT NavHost destinations** (`ui/navigation/AnikiNavHost.kt`) — deliberately.
  navigation-compose 2.9's `NavHost` always swaps content through an internal `AnimatedContent`, even
  with every transition set to `None`; that still costs a recomposition pass separate from anything
  else reading nav state, so the bottom bar (which reads `currentBackStackEntryAsState()` directly)
  and NavHost's own content were structurally never guaranteed to land in the same frame — a real,
  user-visible desync no transition-duration tuning could fix. Fix: Feed/Library render from a plain
  `selectedTab` state read directly in `AnikiNavHost`, no NavHost involved; `NavHost` is scoped to
  only genuinely pushed screens (Detail, Settings, Trash, New Note), which overlay on top via a
  `NONE`-placeholder-start-destination graph. If adding a third top-level tab, extend `AnikiTab` the
  same way — don't add it as a `composable()` destination.
- **`SharingStarted.Eagerly` over `WhileSubscribed(5_000)`** for any StateFlow on a ViewModel that's
  known to persist across tab switches (Library/Feed) — `WhileSubscribed` tears the upstream Flow
  chain down after 5s with no subscriber and pays a real, visible cold-restart cost on the next
  subscribe (e.g. Library's 250ms search debounce + a fresh Room query) exactly when the user
  revisits a tab after a short break. `Eagerly` starts once at construction and never stops for the
  ViewModel's lifetime, matching how long the state is actually needed.
- **`LaunchedEffect(counter)` one-shot-event gotcha**: a ViewModel-persisted counter used as a
  LaunchedEffect key to trigger a one-shot UI action (e.g. "scroll to top once, when this increments")
  will *replay* the action on every composable remount (tab switch away and back) once the counter
  has ever been incremented, even long ago — a fresh `LaunchedEffect` sees "current value" as a brand
  new key on its first run, with no memory of the value having already been handled in a prior
  mount. Fix: snapshot the counter's value into a `remember` baseline at mount time and compare
  against *that*, not against zero (see `FeedScreen.kt`'s `seenScrollToTop`). Same caution applies to
  any other "fire once when this Int/Boolean changes" pattern fed by persisted ViewModel state.
- **Edit-lock pattern** for any user-editable AI-generated field: `xEditedByUser` (Room) /
  `xLocked` (DTO + Postgres column). Set `true` on any user edit; `applyEnrichment()` only
  overwrites the field if the flag is `false`. Wired end-to-end: entity → repository write path →
  `SyncDto` → `SyncRepository.toDto/toEntity` + `itemContentIdentical` → Postgres column →
  `sync/types.ts` → `sync/repo.ts` row mapping → `mergeLogic.ts itemContentEqual`. Currently
  implemented for `summary`, `tags`, and `title`. Follow this exact pattern for any future
  AI-generated, user-editable field.
- **Visual identity**: parchment/oxblood/IBM Plex. Fixed palette tokens live in `ui/theme/Color.kt`
  (`Ink`, `Kon`, `Paper`, `Paper2`, `Seal`, `SealDark`, `Matcha`, `Muted` + derived alpha washes) —
  never invent a new hex; derive tints from existing tokens (see `SealTint1-4` for the pattern).
  The 兄 seal stamp motif means "read/filed by Aniki." Feed is the one dark-ground immersive
  screen (`AnikiTheme(darkGround = true)`); everything else is parchment-light.
- **Schema migrations**: Room — bump `version`, add an additive `Migration` object with a comment
  explaining *why*, no destructive fallback. Mirror on Postgres with a new
  `server/migrations/00N_*.sql` file (forward-only, picked up automatically by the migration runner).
  Bundle a client migration + its server counterpart in the same work session/commit when they're
  part of the same feature.
- **SSRF guard reuse**: any server-side fetch of a URL influenced by external content (article
  page, OG-image candidate) must go through `security/urlGuard.ts`'s `assertSafeUrl` — rejects
  non-http(s) schemes and private/loopback/link-local-resolving hosts.
- **Local-only vs synced fields**: bookkeeping flags for per-device backfill attempts
  (`thumbnailBackfillAttempted`, `titleBackfillAttempted`, `isDemo`, `demoLandingAnimationShown`)
  are deliberately *not* synced — each device backfills/behaves independently, so one device's
  state doesn't affect another's. `isDemo` (the onboarding demo item, see below) additionally
  gates sync (never pushed) and enrichment (never real-enriched) but *not* Library/Feed/search
  visibility — it's otherwise a normal, user-deletable item.
- **Testing**: pure-logic modules stay Compose/DB-free for easy unit testing (`feed/Ranking.kt`,
  `feed/TagWeights.kt`, `feed/PullQuote.kt`, backend `sync/mergeLogic.ts`). Backend tests use
  Node's built-in `node:test` + `mock.module()` for external deps (no Jest/Vitest). Android tests
  use JUnit + MockK.

## 5. Current state / what's done

MVP (6 slices) is complete, functionally and visually. Post-MVP: hardening batch, visual polish
passes, onboarding share-tip, first production deployment, and **Slice 1** of a new post-MVP
feature track are all done and merged to `main`. Everything below is verified end-to-end on a
physical Pixel 8 Pro; deployment pieces are verified against the live Render service.

- **Non-functional + functional hardening**: Room indexes, single-flight Gemini cache, error
  middleware, backend test suite from scratch; Gemini daily-cap retry counting fix, 2mb Express
  body limit, SSRF guard on `/enrich`, per-IP rate limiting, tombstone GC, paginated sync pulls.
- **Visual polish passes**: Feed swipe hint (first-run + idle re-trigger), Library thumbnails
  (OG image → monogram → note glyph, lazy backfill), Feed article/note typographic hero
  (pull-quote), Gemini-generated + user-editable note titles, Library/Feed icon and spacing
  cleanup (`ui/theme/Spacing.kt`).
- **Onboarding "how sharing works" step**: `RootState.SHARE_TIP` (`ui/AppRoot.kt`) fires a real
  `ACTION_SEND` with hardcoded demo content so Aniki appears as a genuine share-sheet target
  (`share/OnboardingDemoContent.kt`, `ItemEntity.isDemo` gates sync/enrichment but not
  visibility). Room schema version 8.
- **Production deployment**: backend live on Render (`aniki-server`, free tier — see §6 for the
  cold-start tradeoff), all secrets from env vars, Postgres over SSL (`DATABASE_SSL`/
  `pool.ts`/`migrate.ts`). Client `BuildConfig.BASE_URL` switches per build type
  (`app/build.gradle.kts`) — debug hits local/LAN, release hits the deployed URL. Release signing
  via git-ignored `keystore.properties` (template: `keystore.properties.example`). Tester
  distribution via `scripts/distribute-release.ps1` → Firebase App Distribution (`aniki-testers`
  group); install flow documented for non-technical testers in `tester-install-guide.md`. Full
  writeups: `docs/specification.md` (product) and `docs/technical-document.md` (implementation).
- **Slice 1 — note editing, universal direct-edit titles, Trash** (commit `3499627`, plus this
  session's fixes on top of it — see git log for the exact sequence):
  - Notes open **directly editable** (title + body, no pencil-tap gate); debounced autosave
    (`ItemDetailViewModel.updateTitleDraft`/`updateBodyDraft`, 600ms/900ms) + flush-on-dispose so
    a screen-leave never loses an in-flight edit. Editing the body resets status to `PENDING` and
    re-enqueues `EnrichmentWorker` (same path as creation) so tags/title stay current — but a
    title-only edit does **not** re-enrich (tags/title-gen are keyed off body content, not
    title — see `ItemRepository.updateNoteBody`'s doc).
  - This direct-edit pattern was then extended to **article/video titles too** (same edit-lock:
    `titleEditedByUser`/`title_locked`), so all three item types share one `TitleField`
    composable in `ItemDetailScreen.kt`. Summaries are **not** editable for any type (read-only
    `SummaryBlock`, no pencil) — and are hidden entirely on notes (redundant next to the user's
    own text; still generated as a free byproduct of the same enrichment call for future
    search/embeddings use, just not rendered).
  - Fixed a real bug along the way: leaving the detail screen mid-edit (via the back button, the
    system back gesture, or the hardware back key) left a blinking cursor visibly lingering on
    top of the destination screen for ~1s. Fix: `BackHandler` + explicit
    `LocalFocusManager.clearFocus(force=true)` + `LocalSoftwareKeyboardController.hide()`,
    synchronous with the nav call, routed through one `dismissEditingAndBack` lambda used by both
    the back button and the system back handler. Note: Android's own "first back press dismisses
    the IME, second press navigates" convention for the hardware/gesture back key is untouched —
    that's expected OS behavior, not something to fight; the fix targets the *lingering artifact*,
    not that two-step convention.
  - **Trash**: soft-delete (`ItemEntity.deletedAt`) already existed as the sync tombstone
    mechanism and already excluded trashed items from Library/Feed/search — Trash mostly just
    exposes it. New: `ItemDao`/`ItemRepository` queries for trashed items, restore, hard-delete,
    and `purgeExpiredTrash(retentionMs)`; `work/TrashPurger.kt` piggybacks on successful sync
    (same pattern as `ThumbnailBackfiller`/`NoteTitleBackfiller`) to hard-delete anything trashed
    >30 days ago, **only if already synced** (`dirty=false`) so an un-pushed tombstone is never
    silently dropped. `ui/trash/TrashScreen.kt` (reachable from Settings → "Trash"): restore,
    per-item delete-forever, bulk empty-trash, both destructive actions behind a confirm dialog
    that shows an extra offline warning (`util/NetworkStatus.kt`'s `isOnline()`) when the device
    is offline at the moment of the tap — manual permanent delete hard-deletes immediately
    regardless of sync state, so if offline the tombstone may never reach the server (known,
    accepted, documented edge case — see `ItemRepository.permanentlyDeleteItem`'s doc).
  - Renamed "Articles" → "Links" everywhere in the UI (Library filter chip, share-sheet
    detected-type pill, onboarding copy) — **display string only**, `ItemType.WEB_ARTICLE` and
    the Postgres/sync schema are unchanged. Globe icon kept (arguably fits "Links" better than it
    fit "Articles").
- **Slice 2 — Feed content, starring, session-stable ordering** (commit `e9fe1a1` for the initial
  build; substantial follow-up bug fixes on top are **uncommitted** — see §8):
  - **Stable session ordering**: `FeedViewModel.onEnter()` re-projects the frozen order onto current
    item data (`reprojectFrozen`) rather than re-ranking, on every Feed re-entry; only the session's
    first-ever open or an explicit pill tap calls `takeFreshSnapshot()`. "Session" = process
    lifetime (confirmed correct via extensive device testing — reset only on cold start). The pager
    also resumes to the exact card last settled on (`sessionLastSettledItemId`/`resumePageIn`), with
    a `DisposableEffect` force-syncing the true current page at teardown time as a safety net against
    a timing gap in the normal settle-tracking effect.
  - **Feed note cards** show real `bodyText` (not the AI summary/pull-quote), truncated at 9 lines
    with a bottom fade + "tap to read full note →" affordance via a content-agnostic
    `FadingTruncatedContent` shell (reusable for a future checklist note type).
  - **Starring from Feed**: `StarStamp` composable (stamp-down scale/squash + oxblood ink-bloom
    ripple on star, ink-fade dissolve on unstar), positioned top-left of the full card area for
    *every* item type including notes (notes originally anchored it to their own parchment card
    instead of the screen — fixed to match articles/videos). `feed/Ranking.kt`'s `buildFeed()`
    partitions starred items into their own block ranked ahead of the rest (not just a score bump).
  - **"Feed updated" pill**: `refreshAvailable` StateFlow flips via a live `observeAllItemsWithTags()`
    collector comparing an "id:starred" fingerprint (deliberately excludes view-tracking fields) against
    the frozen snapshot's baseline. Tap → blur (`animateDpAsState`) + `animateScrollToPage(0, tween(380))`
    — an **explicit tween, not the default spring**, matters: a spring's invisible settling tail keeps
    the coroutine suspended well after the scroll looks done, which was the real source of an
    earlier "blur lingers" bug. Pill sits at `topPad + 56.dp` (below the source/saved-time pill row,
    not overlapping it).
  - **Library "Starred" chip**: `LibraryViewModel.setStarredOnly()`, mutually exclusive with the
    type filter chips (selecting one clears the other).
  - **Two real bugs found and fixed post-implementation**, both worth knowing about if touching Feed
    navigation/pager code again: (1) the pager's settle-tracking `LaunchedEffect` was co-keyed on
    `pagerState.settledPage` AND `items`, which could re-fire during a transient reprojection window
    and record the wrong "last settled" item — fixed by keying on `settledPage` alone. (2) the
    `scrollToTop` counter powering the pill's blur+scroll animation replayed on *every* return to
    Feed once tapped even once, for the rest of the session — see §4's `LaunchedEffect` gotcha entry.
  - **Root cause of a separate, real "bottom bar instant, screen half a second behind" complaint**:
    navigation-compose 2.9's `NavHost` always swaps content via internal `AnimatedContent` even with
    `EnterTransition.None` etc., which structurally can't stay in lockstep with anything reading nav
    state directly (the bottom bar). Fixed by removing Feed/Library from `NavHost` entirely — see
    §4's dedicated bullet. This was **not** a performance issue (confirmed via `dumpsys gfxinfo` —
    no frame anywhere near the reported delay); it was a frame-scheduling structural mismatch.
- **Enrichment failure handling overhaul** (uncommitted — see §8), prompted by an audit of
  `EnrichmentWorker.kt`/`gemini.ts`/`callLimiter.ts`/`ipRateLimiter.ts`/`article.ts`:
  - **Offline-queued state**: `util/NetworkStatus.kt` gained `observeOnline(context): Flow<Boolean>`
    (a `ConnectivityManager.NetworkCallback`-backed Flow, alongside the pre-existing one-shot
    `isOnline()`). Library/Detail show "Waiting for connection to process…" + a new static
    `PausedIndicator` composable (ring + pause bars, `ui/theme/Components.kt`) instead of the
    pulsing "Aniki is reading this…" dot whenever an item is `PENDING` with no connectivity.
  - **Real per-failure messages, not one generic string**: server-side, `EnrichmentError` gained a
    `code: EnrichmentErrorCode` (`server/src/types.ts`) with two new subclasses —
    `FetchFailedError`, `ExtractionFailedError` — thrown from `article.ts`/`youtube.ts`; every
    `/enrich` error response now includes `{error, code}`. Client-side, `ItemEntity` gained two new
    **local-only** (not synced) columns, `errorCode`/`errorMessage` (Room migration 8→9, version 9);
    `EnrichmentWorker` reads and decodes `response.errorBody()` instead of discarding it on failure.
    UI copy is **not** the raw server string — `util/EnrichmentMessages.kt` maps `errorCode` (+
    `itemType`, since `FETCH_FAILED` reads differently for a video's oEmbed call vs. an article
    fetch) to curated short (Library row) / long (Detail card) Aniki-voiced copy, per an explicit
    spec from the user. Retry is conditionally hidden for `QUOTA_EXCEEDED`/`EXTRACTION_FAILED`
    (retrying can't help either case) via `enrichmentCanRetry(errorCode)`.
  - **Quota backoff fix**: `QUOTA_EXCEEDED` specifically skips the normal 30s/60s/120s… exponential
    retry (which used to burn all 5 attempts within minutes against a cap that only resets at UTC
    midnight) and instead calls `EnrichmentScheduler.enqueueAfterQuotaReset()` — a single fresh
    `OneTimeWorkRequest` with `setInitialDelay` timed to just past next UTC midnight. Verified via
    `dumpsys jobscheduler` on-device (a real scheduled job with a ~10h `Minimum latency`, not just
    code review).
  - **Note min-length skip**: notes with a trimmed body under `MIN_NOTE_BODY_LENGTH` (5 chars) skip
    enrichment entirely (`ItemRepository.markEnrichmentSkipped` → straight to `ENRICHED`, no network
    call, no failure state) rather than sending a doomed Gemini request.
  - A message-layout bug found along the way (Retry button compressing to one-letter-per-line next
    to a long wrapping message) was fixed by restructuring `ProcessingStatusCard` from a `Row` to a
    `Column` (message `weight(1f)` wraps, Retry sits below with `widthIn(min = 72.dp)`).

## 6. Known open items / pending decisions

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
- **Manual "delete forever" / "empty trash" offline edge case** (see §5) is mitigated with a
  warning, not fully solved — a genuine fix would mean blocking the UI on a live sync round-trip,
  judged not worth trading away the instant-delete feel for.

## 7. Up next

No new slice/feature has been scoped by the user yet. Both Slice 2 and the enrichment-messaging
overhaul (§5) are functionally complete and verified on-device — **ask the user what's next**
rather than assuming; don't invent scope. If resuming mid-session, the first move is almost
certainly to **commit the pending work** (see §8) unless told otherwise, since a large amount of
verified, working code is currently sitting uncommitted.

## 8. Recent context

- **Substantial uncommitted work sits on top of the last commit** (`e9fe1a1`, "Slice 2: stable feed
  ordering, note content cards, starring, refresh pill"). Run `git status`/`git diff` first thing —
  don't assume the working tree is clean. The uncommitted changes are, in order: (1) Slice 2 polish
  fixes from a manual test round (note star position, refresh pill vertical position, Retry-button
  layout in the failure banner); (2) the entire enrichment failure handling overhaul (§5); (3) three
  rounds of Feed navigation bug fixes (the settle-tracking race, the `scrollToTop` replay bug, and
  the NavHost-vs-bottom-bar structural desync fix) found via the user's own manual testing after
  the above looked done. All of it has been verified on-device by the assistant (screenshots +
  `dumpsys gfxinfo`/`jobscheduler` where relevant) but **not yet committed** — confirm with the user
  whether to commit as one batch or split it up before doing so.
- Room schema is now **version 9** (migration 8→9 added `errorCode`/`errorMessage`, both local-only).
- The physical Pixel 8 Pro has the **debug** build installed, currently running the latest
  uncommitted code from this session. Same debug-vs-release-signing caveat as before applies if the
  device needs to go back to "what testers see" state (see the tester-distribution note in §5).
- Backend dev server + Postgres were both running locally throughout this session's verification
  (`docker compose up -d` + `npm run dev` + `adb reverse tcp:4000 tcp:4000`, not through the
  Dev Toggle script this time) — bring them back up the same way before testing `/enrich`/`/sync`.
- A useful debugging technique worth reusing: `adb shell dumpsys gfxinfo <pkg> reset` then
  `dumpsys gfxinfo <pkg>` after an interaction gives real per-frame render timing (percentiles +
  histogram) — far more reliable than screenshot timing for ruling in/out genuine rendering jank
  vs. a structural/logic bug that merely *looks* like jank. Similarly, `dumpsys jobscheduler` can
  confirm a WorkManager job's actual scheduled delay without waiting for it to fire.
- `CLAUDE.md` exists and points here — no need for the user to manually reference this file.
