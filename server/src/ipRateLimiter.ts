import { config } from "./config.js";

interface Bucket {
  count: number;
  windowStart: number;
}

/**
 * Fixed-window per-IP limiter for /enrich (unauthenticated -- the daily Gemini cap alone lets
 * one abuser exhaust it for every real user before anyone else gets a turn). In-memory only,
 * like the daily cap and content cache; per-process, so it's not a substitute for a real WAF/
 * gateway rate limit once this runs behind more than one instance.
 */
export class InMemoryIpRateLimiter {
  private readonly buckets = new Map<string, Bucket>();

  /** Returns true if `ip` is currently over the limit (and should be rejected with a 429). */
  isLimited(ip: string): boolean {
    const now = Date.now();
    const bucket = this.buckets.get(ip);
    if (!bucket || now - bucket.windowStart >= config.enrichRateLimitWindowMs) {
      this.buckets.set(ip, { count: 1, windowStart: now });
      return false;
    }
    bucket.count += 1;
    return bucket.count > config.enrichRateLimitPerWindow;
  }
}

const limiter = new InMemoryIpRateLimiter();

export function isRateLimited(ip: string): boolean {
  return limiter.isLimited(ip);
}
