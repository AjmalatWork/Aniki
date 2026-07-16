import { createHash } from "node:crypto";
import type { LlmEnrichment } from "./types.js";

export interface EnrichmentCacheStore {
  getOrCompute(
    hash: string,
    compute: () => Promise<LlmEnrichment>
  ): Promise<{ result: LlmEnrichment; cacheHit: boolean }>;
}

/**
 * In-memory only: resets on every server restart. A persistent cache (Redis/DB) is out of
 * scope for this slice — behind the EnrichmentCacheStore interface so swapping it in later is
 * a one-line change (below) instead of touching every caller.
 */
class InMemoryEnrichmentCacheStore implements EnrichmentCacheStore {
  private readonly cache = new Map<string, LlmEnrichment>();
  // Single-flight: concurrent requests for the same content hash join the same in-flight
  // compute() instead of each issuing their own (duplicate, billable) Gemini call. Cleared
  // on both success and failure so a failed call is never cached and can be retried.
  private readonly inFlight = new Map<string, Promise<LlmEnrichment>>();

  async getOrCompute(
    hash: string,
    compute: () => Promise<LlmEnrichment>
  ): Promise<{ result: LlmEnrichment; cacheHit: boolean }> {
    const cached = this.cache.get(hash);
    if (cached !== undefined) return { result: cached, cacheHit: true };

    const existing = this.inFlight.get(hash);
    if (existing) return { result: await existing, cacheHit: true };

    const promise = compute().then((result) => {
      this.cache.set(hash, result);
      return result;
    });
    this.inFlight.set(hash, promise);
    try {
      return { result: await promise, cacheHit: false };
    } finally {
      this.inFlight.delete(hash);
    }
  }
}

const store: EnrichmentCacheStore = new InMemoryEnrichmentCacheStore();

export function hashContent(content: string): string {
  return createHash("sha256").update(content).digest("hex");
}

/**
 * Returns the cached result for `hash`, or runs `compute()` — joining an already in-flight
 * compute() for the same hash if one exists, rather than starting a second one. `cacheHit` is
 * true whenever the caller didn't trigger its own fresh Gemini call (a real cache hit, or a
 * follower that joined someone else's in-flight call).
 */
export function getOrCompute(
  hash: string,
  compute: () => Promise<LlmEnrichment>
): Promise<{ result: LlmEnrichment; cacheHit: boolean }> {
  return store.getOrCompute(hash, compute);
}
