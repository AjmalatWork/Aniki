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
  // real Firebase ID token. Must never be enabled outside local development.
  authDevBypass: process.env.AUTH_DEV_BYPASS === "true",
  // Safety net against a runaway client loop silently blowing through Gemini quota — not a
  // billing system. Counts actual Gemini calls only (cache hits are free and don't count).
  dailyGeminiCallCap: Number(process.env.DAILY_GEMINI_CALL_CAP ?? 500),
};
