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

MVP (6 slices) is complete, functionally and visually. `hardening/non-functional-batch` was
fast-forward merged into `main` (2026-07-16) and the branch was deleted — everything below is on
`main` now. All of it is verified end-to-end on a physical Pixel 8 Pro against real Postgres and a
real Gemini key.

- **Non-functional hardening**: Room indexes, single-flight Gemini cache, error middleware, test
  coverage additions (backend went from 0 tests to a real suite).
- **Functional hardening**: Gemini daily-cap now counts retries correctly, Express body limit
  raised to 2mb, SSRF guard on `/enrich`, per-IP rate limiting, tombstone GC, paginated sync pulls.
- **4-item polish pass**: Feed swipe hint (first-run + idle re-trigger, first session only, now
  also suppressed on the last/only Feed item), Library thumbnails (real OG image → on-palette
  monogram → note glyph, with lazy backfill), Feed article/note typographic hero (pull-quote, no
  image), Gemini-generated + user-editable note titles (with throttled backfill for existing notes).
- **Library/Feed polish pass 2**: Library cards dropped the overlaid type-label text for a small
  below-image icon (globe for articles, reused `PlayArrow` for video, none for notes); the note
  thumbnail now mirrors the app launcher icon's stamp treatment (`SealStampGradient` badge +
  light pencil glyph, `ui/theme/Components.kt`); Feed's bottom zone (hint/text/source/tags) now
  pulls from a shared `Spacing` scale (`ui/theme/Spacing.kt`) and one `BOTTOM_ZONE_INSET` constant.
- **Onboarding "how sharing works" step**: a new `RootState.SHARE_TIP` (`ui/AppRoot.kt`) fires
  after sign-in/guest, before MAIN — a two-screen in-app coach-mark
  (`ui/onboarding/ShareTipScreen.kt`: explain → "your share menu is about to open") primes the
  user, then fires a real `ACTION_SEND` with hardcoded demo content ("Welcome to Aniki" + fixed
  body, `share/OnboardingDemoContent.kt`) so Aniki appears as a genuine share-sheet target. Demo
  content is detected via an invisible zero-width-space marker in `ShareReceiverActivity`, flows
  through the normal share-receive path, but is flagged `ItemEntity.isDemo` — skips real
  enrichment/Gemini entirely (saved pre-`ENRICHED`) and is never synced, while staying otherwise
  visible/deletable in Library/Feed/search like any real item. Every share-sheet outcome resumes
  straight to the Feed (no third "result" screen); if the demo item was received, Feed plays a
  one-time Seal-glow landing pulse (`ItemEntity.demoLandingAnimationShown` persists that it never
  replays). Settings shows a persistent one-line reminder below the account line (no new section).
  Room is now at schema version 8.

## 6. Known open items / pending decisions

- **Not deployed anywhere** — backend/Postgres are local-dev-only; deploy target still TBD
  (Render suggested as a free-tier option, not decided).
- **Backend has no linting and no CI** — `node:test` suite exists and passes, but nothing runs it
  automatically.
- **Backend in-memory state won't survive horizontal scaling** — content cache, rate limiter, and
  metrics are all per-process. Fine for one instance, would need a shared store (Redis/Postgres)
  before running more than one.
- **Direct Share shortcut (sharing-shortcuts) is not implemented.** The onboarding share-tip step
  was explicitly written to not depend on it — Aniki just appears in the normal app row of the
  share sheet rather than the Direct Share strip. Fine as-is; only relevant if that feature is
  ever built later.

## 7. Recent context

- Just finished and committed the onboarding "how sharing works" step (commit `e7b54ac` on
  `main`), including a live iteration cycle with the user (screenshots each round) that changed
  the icons/copy/structure significantly from the original written brief — see git log for the
  full sequence if you need the "why" behind an earlier discarded approach (e.g. the demo item
  was originally going to be hidden from Library/Feed entirely; the user asked for it to be fully
  visible so they can see what happened and delete it manually).
- Backend dev server + Postgres were both running locally during verification; remember to use
  `Aniki Dev Toggle.bat` (or `docker ps` + `npm run dev` manually) to bring them back up in a new
  session before testing anything that touches `/enrich` or `/sync`.
- `CLAUDE.md` now exists and points here — this file no longer needs to be manually referenced by
  the user at the start of a session.
