# Aniki — Technical Document

*Companion to [`docs/specification.md`](specification.md). That doc covers what the product is;
this one covers how it's built — module layout, API contracts, data schema, and deployment
topology. Snapshot as of 2026-07-16.*

## 1. System topology

```
                    ┌─────────────────────┐
                    │   Android Client     │
                    │  (Kotlin + Compose)  │
                    │                       │
                    │  Room (local DB)      │
                    │  WorkManager (bg)     │
                    └──────────┬───────────┘
                               │ HTTPS (Retrofit)
                               │ Firebase ID token (Bearer)
                               ▼
                    ┌─────────────────────┐
                    │  Render Web Service   │
                    │  (Node.js + Express)  │
                    └───┬───────────┬───────┘
                        │           │
             SSL Postgres│           │ Gemini API
                        ▼           ▼
              ┌──────────────┐  ┌─────────────┐
              │  Postgres DB  │  │ Google Gemini│
              │ (Render/ext.) │  │  (gemini-*)  │
              └──────────────┘  └─────────────┘

                    ┌─────────────────────┐
                    │   Firebase Auth       │  ← client-side Google Sign-In,
                    │   (Google Sign-In)    │    server verifies ID tokens
                    └─────────────────────┘

                    ┌─────────────────────┐
                    │ Firebase App          │  ← signed release APKs pushed here
                    │ Distribution          │    for the `aniki-testers` group
                    └─────────────────────┘
```

## 2. Client module layout

`app/src/main/java/com/aniki/anikiai/`

| Package | Responsibility |
|---|---|
| `data/` | Room entities/DAOs, `ItemRepository`, Retrofit `NetworkClient` / `AnikiApi`, `BuildConfig.BASE_URL` per build type |
| `sync/` | `SyncRepository`, DTO ↔ entity mapping, sync-cursor persistence |
| `feed/` | Ranking engine (`Ranking.kt`), tag-affinity weighting (`TagWeights.kt`), pull-quote extraction (`PullQuote.kt`) — all Compose/DB-free, unit-testable in isolation |
| `work/` | `EnrichmentWorker`, `SyncWorker`, `ThumbnailBackfiller`, `NoteTitleBackfiller` |
| `share/` | `ShareReceiverActivity` (share-sheet target), `OnboardingDemoContent` (fixed demo payload for the onboarding tip) |
| `auth/` | Firebase Google Sign-In wiring, guest→account migration |
| `ui/` | Compose screens (`Library`, `Feed`, `Detail`, `Settings`, `onboarding/`), `AnikiNavHost`, `AppRoot` (root state machine), `ui/theme/` (design tokens) |

**Dependency direction**: `UI (Compose)` → `ViewModel` (per-screen, built via `viewModelFactory{}`,
no DI framework) → `Repository` (`ItemRepository` / `SyncRepository`) → `Room` DAOs / `Retrofit`
service.

**Local search**: Room FTS4 is a standalone virtual table (not `@Fts4(contentEntity=...)`, since
`ItemEntity`'s primary key is a UUID string, which that annotation doesn't support cleanly). All
writes to it funnel through one class, `FtsIndexer`, called from both the direct local-write path
and the sync-merge path — this is the only place that's allowed to touch the FTS table, to avoid
divergence between it and `items`.

**Background work** (all via WorkManager):
- `EnrichmentWorker` — unique work per item (keyed by item ID), calls `POST /enrich`
- `SyncWorker` — unique work, runs every 15 minutes and after most local mutations
- `ThumbnailBackfiller` / `NoteTitleBackfiller` — piggyback after every successful sync to lazily
  fill in data for items saved before that feature existed

## 3. Backend module layout

`server/src/`

| Module | Responsibility |
|---|---|
| `index.ts` | Express app wiring, route mounting, `/enrich` and `/extract-thumbnail` handlers, tombstone-GC scheduler |
| `config.ts` | All env-var reads and derived config in one place (see §6) |
| `auth/middleware.ts` | `requireAuth` — verifies Firebase ID tokens (or `X-Debug-Uid` in dev-bypass mode) |
| `sync/` | `routes.ts` (`GET`/`POST /sync`), `repo.ts` (pull/push SQL), `mergeLogic.ts` (LWW conflict resolution), `types.ts` (DTOs) |
| `account/` | `routes.ts` (`GET /account/export`, `DELETE /account`), `repo.ts` |
| `extract/` | `article.ts` (Readability + jsdom), `youtube.ts` (transcript fetch) |
| `gemini.ts` | LLM call wrapper, prompt construction, response parsing |
| `cache.ts` | Content-hash → enrichment result cache (single-flight, in-memory) |
| `callLimiter.ts` | Daily Gemini call cap tracking (in-memory) |
| `ipRateLimiter.ts` | Per-IP sliding-window limiter for unauthenticated endpoints |
| `metrics.ts` | In-memory request/latency counters, exposed at `GET /metrics` |
| `security/urlGuard.ts` | `assertSafeUrl` — SSRF guard for any server-initiated fetch of an externally-supplied URL |
| `db/pool.ts` | `pg` connection pool (SSL-aware, see §7) |
| `db/migrate.ts` | Forward-only migration runner (`npm run migrate`) |

All backend endpoints share one Express error-handling middleware in `index.ts` that guarantees
JSON error responses (never Express's default HTML error page), since the Android client's
`Response<T>` parsing expects JSON on every path.

## 4. API surface

Base URL: debug → local/LAN server; release → `https://aniki-xqm9.onrender.com/` (via
`BuildConfig.BASE_URL`, see §7).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/health` | none | Liveness check (used as Render's health-check path) |
| `GET` | `/metrics` | none | In-memory request/Gemini-call counters (local observability, not a real metrics service) |
| `POST` | `/enrich` | none¹ | Extract + summarize/tag one item via Gemini |
| `POST` | `/extract-thumbnail` | none¹ | Re-check an article's OG/twitter image only — no Gemini call, zero cost against the daily cap |
| `GET` | `/sync?since=<seq>` | Firebase Bearer token | Pull all rows across 4 tables changed after cursor `since`, paginated |
| `POST` | `/sync` | Firebase Bearer token | Push local mutations (items/tags/itemTags/engagementEvents) |
| `GET` | `/account/export` | Firebase Bearer token | Full JSON dump of the caller's data |
| `DELETE` | `/account` | Firebase Bearer token | Permanently delete the caller's account and all rows |

¹ `/enrich` and `/extract-thumbnail` are intentionally unauthenticated (stateless, hold no user
data) but are protected by a per-IP rate limiter (`ipRateLimiter.ts`) since an unauthenticated
endpoint is the one place a single abusive caller could exhaust the shared daily Gemini quota for
every real user.

### `POST /enrich` request/response

```ts
// Request
{ id: string; type: "WEB_ARTICLE" | "YOUTUBE_VIDEO" | "NOTE";
  sourceUrl: string | null; bodyText: string | null }

// Response (200)
{ id: string; title: string | null; summary: string; category: string;
  tags: string[]; entities: { people: string[]; places: string[]; dates: string[] };
  thumbnailUrl: string | null; eventDate: string | null }
```
`429` if the daily Gemini cap or per-IP rate limit is hit; `422` on extraction/enrichment failure;
`400` on missing `id`/`type`.

### `GET /sync` response shape

```ts
{ items: ItemDto[]; tags: TagDto[]; itemTags: ItemTagDto[];
  engagementEvents: EngagementEventDto[]; nextCursor: number; hasMore: boolean }
```
The client loops pull pages while `hasMore` is true, but persists its cursor **once**, only after
the full loop completes — a crash mid-pull just re-pulls from the last committed cursor rather than
losing or skipping rows.

## 5. Data model (Postgres)

One shared sequence, `sync_seq`, is used as the `seq` column default across all four synced
tables — deliberately *not* four independent `BIGSERIAL`s, because a per-table sequence would let
a sync cursor advance past one table's value and permanently skip a later row in another table
that happened to reuse the same numeric `seq`.

| Table | Key columns | Notes |
|---|---|---|
| `users` | `id` (Firebase uid, PK), `email` | Created on first authenticated request (`ensureUser`) |
| `items` | `id` (UUID, PK, client-generated), `user_id`, `type`, `source_url`, `title`, `body_text`, `summary`, `thumbnail_url`, `category`, `entities` (JSONB), `event_date`, `status`, `is_starred`, `summary_locked`, `tags_locked`, `title_locked`, `updated_at` (LWW clock), `deleted_at` (tombstone), `seq` | The core saved-item table |
| `tags` | `id` (UUID, PK), `user_id`, `label`, `origin`, `updated_at`, `deleted_at`, `seq` | Unique on `(user_id, label)` |
| `item_tags` | `(item_id, tag_id)` composite PK, `user_id`, `updated_at`, `deleted_at`, `seq` | Join table |
| `engagement_events` | `id` (UUID, PK), `user_id`, `item_id`, `event_type`, `value`, `created_at`, `seq` | Feeds the Feed ranking engine's tag-affinity term |

**Edit-lock pattern** (`summary_locked`, `tags_locked`, `title_locked`): set `true` the moment a
user hand-edits that field; enrichment is only ever allowed to overwrite the field when its lock is
`false`. This is threaded through the full stack: Room entity → repository write path → sync DTO →
`SyncRepository.toDto/toEntity` (+ `itemContentIdentical`) → Postgres column →
`server/src/sync/types.ts` → `sync/repo.ts` row mapping → `mergeLogic.ts itemContentEqual`. Any
future AI-generated, user-editable field should follow this exact chain.

**Migrations**: additive-only, forward-only. Room bumps `version` and adds a `Migration` object
(currently at schema version 8); Postgres gets a matching `server/migrations/00N_*.sql` file,
picked up automatically and idempotently by `db/migrate.ts` (tracks applied files in a
`_migrations` table). The two are shipped together in the same commit when part of one feature.

## 6. Configuration (backend env vars)

All read once in `server/src/config.ts` — no secret is ever hardcoded in source.

| Var | Required | Purpose |
|---|---|---|
| `DATABASE_URL` | yes | Postgres connection string |
| `DATABASE_SSL` | no | Forces `ssl: { rejectUnauthorized: false }` on the `pg` pool; auto-true when `NODE_ENV=production` unless explicitly set `false` |
| `GEMINI_API_KEY` | yes | Google Gemini API key |
| `GEMINI_MODEL` | no | Overrides the default `gemini-3.5-flash` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | one of these two | Service-account JSON pasted directly (preferred on Render — no file mount needed) |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | one of these two | Path to a service-account JSON file (used when a secret file is mounted instead) |
| `AUTH_DEV_BYPASS` | no | Local-dev-only `X-Debug-Uid` header auth bypass; hard-disabled whenever `NODE_ENV=production` regardless of this flag's value |
| `DAILY_GEMINI_CALL_CAP` | no, default 500 | Runaway-loop safety net, not a billing system |
| `ENRICH_RATE_LIMIT_PER_WINDOW` / `ENRICH_RATE_LIMIT_WINDOW_MS` | no, defaults 20 / 60000 | Per-IP limiter on unauthenticated endpoints |
| `TOMBSTONE_RETENTION_DAYS` | no, default 90 | How long deleted rows survive before hard-delete |
| `SYNC_PULL_PAGE_SIZE` | no, default 500 | Max rows per `/sync` pull page across all 4 tables combined |

## 7. Build & deployment

**Client — environment switching**: `app/build.gradle.kts` defines `buildConfigField("String",
"BASE_URL", …)` per build type — `debug` points at `http://127.0.0.1:4000/` (emulator reaches this
via the `10.0.2.2` alias automatically; a physical device needs `adb reverse tcp:4000 tcp:4000`
first), `release` points at the deployed Render URL. `NetworkClient` reads `BuildConfig.BASE_URL`
directly — no other code branches on build type.

**Client — release signing**: `keystore.properties` (git-ignored, template at
`keystore.properties.example`) supplies `storeFile`/`storePassword`/`keyAlias`/`keyPassword` to a
`signingConfigs.release` block. Absent locally, `assembleRelease` still produces a build (unsigned)
rather than failing — only actual Play/tester distribution requires the real keystore.

**Backend — Render**: `render.yaml` at the repo root defines a single web service with `rootDir:
server`, `buildCommand: npm install --include=dev && npm run build` (dev deps are required at
build time for `tsc` to resolve `@types/*` packages — `npm install` alone skips them once
`NODE_ENV=production` is set), `startCommand: npm start`, health check at `/health`. All secrets
are set as `sync: false` env vars filled in through the Render dashboard, never committed.
Migrations currently run manually (`npm run migrate` against the External Database URL, or folded
into the start command as `npm run migrate && npm start`) since Pre-Deploy Command is a paid-tier
Render feature.

**Tester distribution**: `scripts/distribute-release.ps1` — one PowerShell command
(`-ReleaseNotes "..."`) that builds `assembleRelease`, locates the signed APK (falling back to the
unsigned one with a warning if no keystore is configured), and runs `firebase
appdistribution:distribute` against app ID `1:594898051123:android:7519232a901620e9454346` and
group `aniki-testers`. The script self-sets `JAVA_HOME` to Android Studio's bundled JBR if not
already set in the environment, since this dev machine has no standalone JDK.

## 8. Testing

- **Android**: JUnit + MockK. Pure-logic modules (`feed/Ranking.kt`, `feed/TagWeights.kt`,
  `feed/PullQuote.kt`) are deliberately kept free of Compose/Room dependencies specifically so they
  can be unit-tested without instrumentation.
- **Backend**: Node's built-in `node:test` + `mock.module()` for mocking external deps (no
  Jest/Vitest). `sync/mergeLogic.test.ts`, `gemini.test.ts`, `ipRateLimiter.test.ts`, extraction
  tests, etc. No CI runs this automatically yet — it's a local `npm test` only.

## 9. Known technical debt

- Backend in-memory state (content cache, rate limiter, metrics counters) is per-process — would
  need a shared store (Redis or a Postgres-backed table) before running more than one Render
  instance.
- No CI/linting pipeline on the backend.
- Migrations run manually against production rather than as an automated pre-deploy step (Render
  plan limitation, not a design choice).
- Direct Share (Android sharing-shortcuts) intentionally not implemented — the onboarding demo was
  written to not depend on it.
