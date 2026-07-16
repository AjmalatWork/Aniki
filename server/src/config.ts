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
  // Path to the Firebase Admin service-account JSON. Unset until the Firebase project exists;
  // see AUTH_DEV_BYPASS below for local development in the meantime.
  firebaseServiceAccountPath: process.env.FIREBASE_SERVICE_ACCOUNT_PATH,
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
};
