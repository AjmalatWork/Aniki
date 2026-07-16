import { config } from "./config.js";

export interface CallLimiterStore {
  /** Returns true and records the call if under the cap; false (and does not record) if at/over it. */
  tryConsume(): boolean;
  snapshot(): { date: string; count: number; cap: number };
}

function utcDayKey(): string {
  return new Date().toISOString().slice(0, 10); // YYYY-MM-DD
}

/**
 * Global daily counter on actual Gemini calls (cache hits don't count — they're free).
 * In-memory only, resets on restart and at UTC day rollover. A safety net against a runaway
 * client loop silently blowing through API quota, not a billing system — deliberately simple.
 * Global (not per-user) because /enrich is unauthenticated (see index.ts's comment on that).
 * Behind the CallLimiterStore interface so a shared/persistent counter (needed the moment this
 * runs as more than one process) is a one-line swap below, not a scattered rewrite.
 */
class InMemoryCallLimiterStore implements CallLimiterStore {
  private dayKey = utcDayKey();
  private callCount = 0;

  private rolloverIfNewDay(): void {
    const today = utcDayKey();
    if (today !== this.dayKey) {
      this.dayKey = today;
      this.callCount = 0;
    }
  }

  tryConsume(): boolean {
    this.rolloverIfNewDay();
    if (this.callCount >= config.dailyGeminiCallCap) return false;
    this.callCount += 1;
    return true;
  }

  snapshot(): { date: string; count: number; cap: number } {
    this.rolloverIfNewDay();
    return { date: this.dayKey, count: this.callCount, cap: config.dailyGeminiCallCap };
  }
}

const store: CallLimiterStore = new InMemoryCallLimiterStore();

export function tryConsumeGeminiCall(): boolean {
  return store.tryConsume();
}

export function getCallLimiterSnapshot(): { date: string; count: number; cap: number } {
  return store.snapshot();
}
