# Aniki — High-Level Specification

*Snapshot as of 2026-07-16, reflecting the state of `main` after MVP completion, the hardening
batch, visual polish passes, onboarding share-tip, and the first Render deployment + Firebase App
Distribution test rollout.*

## 1. Product overview

**Aniki** ("big brother" in Japanese) is an Android app for saving anything — shared links,
YouTube videos, Instagram posts, plain notes — with zero friction via the OS share sheet. An AI
pipeline reads, summarizes, categorizes, and tags each saved item automatically.

**Core thesis**: people save things constantly (links, videos, articles) but almost never revisit
them. Aniki's bet is that the bottleneck isn't *capture*, it's *retrieval* — so the product invests
in making saved items easy to rediscover and skim, not just easy to store.

**Two primary surfaces**:
- **Library** — a searchable, filterable list of everything saved.
- **Feed** — a full-screen, TikTok-style vertical swipe experience, ranked by relevance rather than
  recency. This is the app's differentiator: instead of a chronological dump, Feed resurfaces
  older or under-engaged items alongside recent ones based on a deterministic ranking model.

## 2. Core features

| Feature | Status |
|---|---|
| Save via Android share sheet (links, YouTube, notes) | ✅ Done |
| AI enrichment (title, summary, category, tags, entities, event date) via Gemini | ✅ Done |
| Article extraction (Readability) + OG-image thumbnail | ✅ Done |
| YouTube transcript extraction | ✅ Done |
| Library — search (FTS4), type icons, thumbnails | ✅ Done |
| Feed — ranked full-screen swipe, pull-quote hero for text items | ✅ Done |
| User edit-lock — user edits to title/summary/tags are never overwritten by re-enrichment | ✅ Done |
| Multi-device sync (delta sync, last-write-wins) | ✅ Done |
| Google Sign-In auth + guest mode with later account migration | ✅ Done |
| Onboarding — live share-sheet demo teaching users how sharing works | ✅ Done |
| Settings — data export, account deletion | ✅ Done |
| Visual identity pass (parchment/oxblood theme, 兄 seal motif) | ✅ Done |
| Backend deployed (Render) | ✅ Done |
| Release signing + Firebase App Distribution to testers | ✅ Done |
| Backend CI / linting | ❌ Not started |
| Direct Share (sharing-shortcuts) target | ❌ Not implemented, not planned near-term |

## 3. Tech stack

**Client** — Android native
- Kotlin + Jetpack Compose, single-activity navigation (`AnikiNavHost`) plus a dedicated
  `ShareReceiverActivity` for the share-sheet target
- Room (+ a standalone FTS4 virtual table for search) for local persistence
- WorkManager for background enrichment and sync
- Retrofit + kotlinx.serialization for networking
- Coil3 for image loading
- Firebase Auth (Google Sign-In) for identity
- DataStore Preferences for lightweight local settings
- No DI framework (Hilt/Koin) — manual `viewModelFactory{}` wiring per screen
- AGP 9.2.1 with built-in Kotlin; minSdk 26, targetSdk 36, compileSdk 37

**Backend** — Node.js + TypeScript
- Express, raw `pg` (no ORM), hand-rolled forward-only SQL migration runner
- `firebase-admin` to verify client ID tokens server-side
- `@mozilla/readability` + `jsdom` for article extraction
- `youtube-transcript` for video captions
- Google Gemini (`@google/genai`, default model `gemini-3.5-flash`) for enrichment
- Deployed on **Render** as a Node web service (`render.yaml` at repo root), with managed/external
  Postgres reached over SSL

**Infra / tooling**
- Postgres (Render-managed or external in prod; local Docker via `docker-compose.yml` in dev)
- `scripts/aniki-dev-toggle.ps1` (`Aniki Dev Toggle.bat`) — one-click local backend + `adb reverse`
- `scripts/distribute-release.ps1` — builds a signed release APK and pushes it to Firebase App
  Distribution's `aniki-testers` group
- Backend tests: Node's built-in `node:test` (no Jest/Vitest)
- Android tests: JUnit + MockK

## 4. Architecture

### Client
`UI (Compose) → ViewModel → Repository (ItemRepository, SyncRepository) → Room / Retrofit`

- `FtsIndexer` is the single choke point that keeps the FTS4 search index in sync with both the
  local-write and sync-merge paths.
- Background work: `EnrichmentWorker` (per-item, unique work keyed by item ID), `SyncWorker`
  (unique work, runs every 15 minutes plus after most local mutations), and
  `ThumbnailBackfiller` / `NoteTitleBackfiller` which piggyback after each successful sync to
  lazily fill in data for items saved before a feature existed.

### Backend
Express routes:
- `/enrich` — unauthenticated, stateless, does the actual Gemini + extraction work
- `/sync` — Firebase-authenticated, delta push/pull
- `/account` — export / delete
- `/extract-thumbnail` — unauthenticated, Gemini-free lazy thumbnail backfill

State is currently **per-process** (in-memory content-hash cache, per-IP rate limiter, metrics
counters) — fine for a single Render instance, would need a shared store (Redis/Postgres) before
horizontal scaling.

### Sync protocol
Row-level last-write-wins, keyed on two independent clocks:
- `updatedAt` — client device-timestamp, used as the conflict-resolution clock
- `seq` — a single shared Postgres sequence across all four synced tables, used purely as a
  replication cursor (not for conflict resolution)

Pulls are paginated; page boundaries are computed via a global-seq-ordered UNION query so no row
is skipped across a page split, but the client only persists its cursor once at the very end of a
full pull — preserving crash-resilience (a crash mid-pull just re-pulls from the last committed
cursor, never loses or skips rows). Deletes propagate as tombstones (`deleted_at`), garbage
collected daily past a retention window.

### Feed ranking
Deterministic, not ML-based (`feed/Ranking.kt`): recency decay, a resurfacing term for
under-engaged items, tag affinity learned from engagement history, date-proximity boost, a
seen-penalty, then a diversity pass. Feed order is a **frozen per-session snapshot** — it does not
re-rank live as a Room `Flow` would, since re-ordering mid-swipe would be jarring.

## 5. Data model conventions

- **Edit-lock pattern**: any AI-generated field a user can hand-edit (currently `summary`, `tags`,
  `title`) carries a paired `xEditedByUser` (Room) / `xLocked` (DTO + Postgres) flag. Once set, a
  future `applyEnrichment()` call never overwrites that field. Wired end-to-end through the entity,
  repository, sync DTOs, and Postgres schema.
- **Local-only vs. synced fields**: per-device bookkeeping (backfill-attempted flags, demo-item
  markers) is deliberately excluded from sync — each device tracks its own backfill state
  independently.
- **Schema migrations**: additive-only on both Room (versioned `Migration` objects) and Postgres
  (`server/migrations/00N_*.sql`, picked up automatically by the migration runner), shipped
  together when part of the same feature. Room is currently at schema version 8.
- **SSRF protection**: any server-side fetch of an externally-influenced URL goes through
  `security/urlGuard.ts`, which rejects non-http(s) schemes and private/loopback/link-local hosts.

## 6. Visual identity

Parchment/oxblood palette with IBM Plex typography. Fixed tokens live in `ui/theme/Color.kt`
(`Ink`, `Kon`, `Paper`, `Paper2`, `Seal`, `SealDark`, `Matcha`, `Muted`) — new colors are always
derived tints of these, never new hex values. The 兄 seal-stamp motif signifies "read/filed by
Aniki." Feed is the app's one dark-ground immersive screen; everything else is parchment-light.

## 7. Deployment & distribution (current)

- **Backend**: live on Render as a web service (`aniki-server`), reading all secrets
  (`DATABASE_URL`, `GEMINI_API_KEY`, `FIREBASE_SERVICE_ACCOUNT_JSON`) from environment variables,
  connecting to Postgres over SSL. Health-checked at `/health`.
- **Client build config**: Retrofit base URL is switched via `buildConfigField` per build type —
  debug points at a local/LAN server, release points at the deployed Render URL.
- **Release signing**: a dedicated keystore (`keystore.properties`, git-ignored) signs release
  builds; `.jks` files and the properties file are excluded from version control.
- **Tester distribution**: `scripts/distribute-release.ps1` builds a signed release APK and
  uploads it to Firebase App Distribution's `aniki-testers` group in one command. Testers install
  via a self-serve flow (`tester-install-guide.md`) that requires the Firebase App Tester helper
  app plus a one-time "install unknown apps" permission grant.

## 8. Known limitations / open items

- Backend has no linting and no CI — the `node:test` suite exists and passes locally but nothing
  runs it automatically on push.
- Backend in-memory state (cache, rate limiter, metrics) won't survive horizontal scaling; a shared
  store would be needed before running more than one instance.
- Direct Share (Android sharing-shortcuts) is not implemented — Aniki appears in the normal share
  sheet app row, not the Direct Share strip. Deliberate scope cut, not a bug.
- Currently in **closed testing** via Firebase App Distribution (`aniki-testers` group) — not on
  the Play Store.
