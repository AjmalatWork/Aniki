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

## 7. Up next: Slice 2 (Feed content, starring, session-stable ordering) — NOT STARTED

Full spec below, exactly as given by the user — read completely before touching any code, since
item 1 is foundational to items 3 and 4 and touches core ranking/session state.

**Context**: opening an item from Feed and navigating back can currently land the user on a
different item than the one they opened, because the feed re-ranks/reorders live mid-session.
This entire slice must avoid reintroducing that failure mode.

1. **Stable feed session ordering.** Snapshot the Feed's order on load and hold it stable for the
   whole session, including navigating into detail and back. Background ranking changes (new
   scores, newly enriched items, etc.) must not visually reshuffle the list mid-swipe — the new
   order only applies on the next explicit refresh (the item-4 indicator, app reopen, or
   pull-to-refresh at the top).
2. **Feed note cards show real content, not the AI summary.** Style consistent with the existing
   article typographic-hero treatment. Truncate long content with a bottom fade (never make the
   card internally scrollable — conflicts with vertical swipe-to-advance); add a "tap to read full
   note" affordance when truncated, opening the detail screen. Untruncated short notes render
   fully. Tapping any note card (truncated or not) opens detail — no inline editing from the Feed
   card. Scoped to Feed cards only (Library row / detail screen unaffected). Keep the
   truncation/expand logic reasonably generic, not hardcoded to prose — a future checklist-style
   note type will need the same pattern applied to list items instead of paragraphs.
3. **Starring from the Feed card**, with a stamp-down animation (scale up → squash + oxblood
   ink-bloom/ripple from the stamp point, background blurs briefly then clears) that is purely
   visual — must not reorder the live session (per item 1). After settling, a persistent oxblood
   star mark renders top-left of the card heading (mirroring the existing top-right 兄 seal).
   Unstarring (from the Feed mark or the existing detail-screen toggle, which is unchanged)
   dissolves the mark with an ink-fade (fade + slight drift). Starring/unstarring updates
   underlying data (Library/search/filter) immediately in both directions — only the Feed's
   *visual position* stays frozen until the next refresh.
4. **Refresh-available indicator**: oxblood pill, fixed at the top of the screen (not attached to
   the card stack), appears whenever the session's snapshot has gone stale (starring, unstarring,
   newly-enriched items, etc.), copy like "Feed updated." Slides down with a spring/overshoot
   entrance. Tap → background blurs, feed fast-scrolls to position 1, reveals the refreshed order
   (starred items grouped at the top, sorted by the same relevance scoring, not recency-of-star),
   blur clears once settled. No persistent star count anywhere — this pill is the only signal, and
   it's general-purpose (not starring-specific — anything that changes the ranking should trigger it).
5. **Library "Starred" filter chip**, alongside All/Links/Videos/Notes, showing only starred items.

**Architecture already in place that this builds on** (read before designing new state):
- `ui/feed/FeedViewModel.kt` **already** takes a frozen-per-session snapshot in `refresh()` —
  `FeedUiState.Content(items: List<ItemWithTags>, ...)` is a plain immutable list, not a live Room
  `Flow`. `onToggleStar()` already does an in-place `patchItem()` (map + copy, no reorder,
  no re-sort) rather than touching ordering — item 3's "don't reorder, just patch the star flag"
  requirement is *already the existing pattern* for starring; the new work is the animation, the
  persistent mark, and making the refresh-available pill *detect* that a patch happened.
  `refresh()` is already the single place a new snapshot is taken (called on Feed-open); item 1's
  "next explicit refresh" and item 4's pill-tap should both funnel through something adjacent to
  this same method.
  `companion object`'s `sessionPrefsLoaded`/`isFirstSessionEver` (for the swipe-hint feature) are
  already scoped to *process lifetime*, not ViewModel lifecycle — Navigation-Compose can
  retain/recreate `FeedViewModel` across Feed↔Library tab switches within one process run. Whatever
  "session" means for item 1's stable ordering should probably follow this same precedent (process
  lifetime, reset only on process death) unless the user's answer to the open question below says
  otherwise.
- `feed/Ranking.kt`'s `buildFeed()` is pure/offline (no Android/DB deps, unit-testable directly):
  scores every `ENRICHED`-status candidate (recency decay, resurface term, tag affinity, date
  proximity, star bonus `w.wStar`, seen-penalty), sorts, then a diversity post-pass caps
  same-type run lengths. Star already contributes to score (`w.wStar * star`) — so "starred items
  grouped at top" in item 4's refreshed order may fall out mostly for free from existing scoring
  plus a possible weight bump, rather than needing bespoke grouping logic. Worth checking the
  actual sort behavior empirically before adding a separate "starred-first" branch.
- Feed card rendering lives in `ui/feed/FeedScreen.kt` (not yet read this session — read fully
  before implementing items 2/3/4's card-level visuals). Note-vs-article pull-quote logic already
  exists there per file comments ("Prefer the AI summary's pull-quote... Mirrors NoteCard's
  now-shared pull-quote extraction") — relevant prior art for item 2's truncation display, though
  item 2 explicitly wants raw note content instead of the AI summary/pull-quote, so this is
  precedent for the *mechanism* (truncation, hero styling) not the *content source*.

**Open question the user asked to flag, not yet answered**: how should "session" be scoped for
item 1 — does backgrounding the app end a session (next foreground = fresh refresh), or does it
persist until the user explicitly refreshes (matching the swipe-hint precedent's process-lifetime
scoping above)? Ask before implementing if the user hasn't clarified by the time this resumes.

## 8. Recent context

- Slice 2 above was scoped in full by the user but **implementation has not started** — the
  session ended right after reading `FeedViewModel.kt` and `feed/Ranking.kt` (notes captured in
  §7 above). Next session should read `FeedScreen.kt` in full next, then plan before writing code.
- Slice 1 (§5) is committed on `main` (commit `3499627`) and was manually verified on-device by
  the user themselves for the final round of fixes (the assistant's own on-device verification
  pass was interrupted partway through by the user confirming it already looked correct).
- The physical Pixel 8 Pro currently has the **debug** build installed (not the tester release
  build) — debug and release use different signing keys, and testing Slice 1 required uninstalling
  the release build Firebase App Distribution had pushed. Reinstall the release build (or run
  `distribute-release.ps1` again) if you need the device back in "what testers see" state.
- Backend dev server + Postgres were both running locally during this session's verification;
  bring them back up (`Aniki Dev Toggle.bat`, or `docker compose up -d` + `npm run dev` manually +
  `adb reverse tcp:4000 tcp:4000`) before testing anything that touches `/enrich` or `/sync` on
  the emulator/device again.
- `CLAUDE.md` exists and points here — no need for the user to manually reference this file.
