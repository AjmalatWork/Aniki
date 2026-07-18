import "dotenv/config";

function requireEnv(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

export const config = {
  port: Number(process.env.PORT ?? 4000),
  geminiApiKey: requireEnv("GEMINI_API_KEY"),
  // Current GA Flash model as of this slice; overridable for when it's under heavy load
  // (Google's public capacity, unrelated to this codebase) or a newer Flash model ships.
  geminiModel: process.env.GEMINI_MODEL ?? "gemini-3.5-flash",
  databaseUrl: requireEnv("DATABASE_URL"),
  // Managed Postgres providers (Render included) terminate with a cert their pool of CAs won't
  // validate cleanly, so `rejectUnauthorized: false` is the standard escape hatch. Off by default
  // for local/Docker Postgres, on automatically in production; DATABASE_SSL overrides either way.
  databaseSsl:
    process.env.DATABASE_SSL === "true" ||
    (process.env.NODE_ENV === "production" && process.env.DATABASE_SSL !== "false"),
  // Path to the Firebase Admin service-account JSON. Unset until the Firebase project exists;
  // see AUTH_DEV_BYPASS below for local development in the meantime.
  firebaseServiceAccountPath: process.env.FIREBASE_SERVICE_ACCOUNT_PATH,
  // Alternative to firebaseServiceAccountPath: the service-account JSON itself, for hosts (e.g.
  // Render) where pasting an env var is easier than mounting a secret file. Takes precedence
  // over the path when both are set.
  firebaseServiceAccountJson: process.env.FIREBASE_SERVICE_ACCOUNT_JSON,
  // Dev-only escape hatch: when true, /sync trusts an X-Debug-Uid header instead of verifying a
  // real Firebase ID token. Must never be enabled outside local development — hard-disabled in
  // production regardless of the env var, so a leaked/misconfigured AUTH_DEV_BYPASS=true can't
  // become a full auth bypass on a deployed server.
  authDevBypass: process.env.AUTH_DEV_BYPASS === "true" && process.env.NODE_ENV !== "production",
  // Safety net against a runaway client loop silently blowing through Gemini quota — not a
  // billing system. Counts actual Gemini calls only (cache hits are free and don't count).
  dailyGeminiCallCap: Number(process.env.DAILY_GEMINI_CALL_CAP ?? 500),
  // Per-IP guard on /enrich (unauthenticated, so the daily cap alone lets one abuser exhaust it
  // for every real user): max requests per IP within a rolling window before a 429.
  enrichRateLimitPerWindow: Number(process.env.ENRICH_RATE_LIMIT_PER_WINDOW ?? 20),
  enrichRateLimitWindowMs: Number(process.env.ENRICH_RATE_LIMIT_WINDOW_MS ?? 60_000),
  // How long a tombstoned row is kept before being hard-deleted. A device offline longer than
  // this will re-surface an old delete as if the row still existed on its next sync.
  tombstoneRetentionDays: Number(process.env.TOMBSTONE_RETENTION_DAYS ?? 90),
  // Max rows returned per /sync pull page, across all 4 synced tables combined.
  syncPullPageSize: Number(process.env.SYNC_PULL_PAGE_SIZE ?? 500),
  // SEC3 (maintainability audit): a NOTE's bodyText has no length cap today short of the blanket
  // 2mb express.json() body limit -- a pathological note flows untruncated through hashing,
  // storage, and every sync round-trip before finally getting truncated at the last possible
  // moment, inside enrichWithGemini's own CONTENT_CHAR_BUDGET (gemini.ts, 12,000 chars), right
  // before the LLM call. 50,000 chars (~50KB, ~8-10k words) is roughly 4x that budget -- generous
  // headroom for a legitimately long pasted note, while bounding the worst case for everything
  // upstream of the LLM call (hash cost, Postgres row size, per-device sync payload size) to a
  // small, predictable ceiling instead of "whatever fits under 2mb." Enforced at the /enrich
  // boundary (server/src/index.ts) since that's the actual unauthenticated network entry point;
  // the client (ItemRepository.createNote/updateNoteBody) also truncates to the same constant as
  // a defensive backstop, but a client-side cap alone doesn't protect the server from a direct
  // caller. Flagged for review -- this is a reasoned starting point, not a measured one.
  maxNoteBodyChars: Number(process.env.MAX_NOTE_BODY_CHARS ?? 50_000),
  // SEC3 (maintainability audit): /sync push currently accepts any number of rows that fits under
  // the 2mb body limit, each upserted sequentially inside one Postgres transaction (pushChanges,
  // repo.ts) -- an unusually large batch holds row locks and a transaction open for a
  // correspondingly long time, which affects every other request against those rows, not just the
  // slow request itself.
  //
  // This was originally set to 2,000 and had to be raised: guest mode never syncs, and
  // EngagementEventPurger only prunes already-synced (dirty=0) rows, so every SHOWN/DWELL/OPENED
  // event a guest ever generates in the Feed sits dirty=1 indefinitely and lands in one shot on
  // markAllLocalRowsDirty() at account migration. At ~2 events per card view (SHOWN + DWELL --
  // see FeedViewModel.onPageSettled), even moderate guest usage (a couple hundred card views/day
  // for a few weeks before ever signing in) comfortably produces well over 10,000 events alone --
  // 2,000 was tighter than the pre-existing 2mb byte limit itself for this exact scenario, making
  // it strictly worse than having no row cap at all. 20,000 (summed across
  // items+tags+itemTags+engagementEvents) sits comfortably above any realistic guest accumulation
  // while staying a genuine backstop against the one payload shape the byte limit alone doesn't
  // guard well: a very large number of small rows (tag/item-tag spam, ~90 bytes/row -- the byte
  // limit alone doesn't bind until roughly 23,000 of those). The client still has no push-side
  // pagination today, so an even more extreme accumulation (or a deliberately pathological
  // payload) falls back to the pre-existing 2mb limit, unchanged by this cap either way. If usage
  // data ever suggests real accounts are approaching this, the right fix is push-side pagination,
  // not a higher number here.
  maxSyncPushRows: Number(process.env.MAX_SYNC_PUSH_ROWS ?? 20_000),
};
