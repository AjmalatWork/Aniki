import { config } from "./config.js";

/**
 * Global daily counter on actual Gemini calls (cache hits don't count — they're free).
 * In-memory only, resets on restart and at UTC day rollover. A safety net against a runaway
 * client loop silently blowing through API quota, not a billing system — deliberately simple.
 * Global (not per-user) because /enrich is unauthenticated (see index.ts's comment on that).
 */
let dayKey = utcDayKey();
let callCount = 0;

function utcDayKey(): string {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD
}

function rolloverIfNewDay(): void {
  const today = utcDayKey();
  if (today !== dayKey) {
    dayKey = today;
    callCount = 0;
  }
}

/** Returns true and records the call if under the cap; false (and does not record) if at/over it. */
export function tryConsumeGeminiCall(): boolean {
  rolloverIfNewDay();
  if (callCount >= config.dailyGeminiCallCap) return false;
  callCount += 1;
  return true;
}

export function getCallLimiterSnapshot(): { date: string; count: number; cap: number } {
  rolloverIfNewDay();
  return { date: dayKey, count: callCount, cap: config.dailyGeminiCallCap };
}
