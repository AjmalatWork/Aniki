interface EndpointStats {
  count: number;
  errors: number;
  totalLatencyMs: number;
  maxLatencyMs: number;
}

export interface MetricsStore {
  record(endpoint: string, latencyMs: number, isError: boolean): void;
  snapshot(): Record<string, unknown>;
}

/**
 * In-memory per-endpoint counters and latency stats — enough to answer "is this working"
 * without a debugger or a metrics service (Slice 6 observability task). Resets on server
 * restart; not persisted, not exported anywhere. Behind the MetricsStore interface so a
 * shared/persistent store (needed the moment this runs as more than one process) is a
 * one-line swap below, not a scattered rewrite.
 */
class InMemoryMetricsStore implements MetricsStore {
  private readonly stats = new Map<string, EndpointStats>();

  record(endpoint: string, latencyMs: number, isError: boolean): void {
    const existing = this.stats.get(endpoint) ?? { count: 0, errors: 0, totalLatencyMs: 0, maxLatencyMs: 0 };
    existing.count += 1;
    if (isError) existing.errors += 1;
    existing.totalLatencyMs += latencyMs;
    existing.maxLatencyMs = Math.max(existing.maxLatencyMs, latencyMs);
    this.stats.set(endpoint, existing);
  }

  snapshot(): Record<string, unknown> {
    const endpoints: Record<string, unknown> = {};
    for (const [endpoint, s] of this.stats.entries()) {
      endpoints[endpoint] = {
        count: s.count,
        errors: s.errors,
        errorRate: s.count > 0 ? Number((s.errors / s.count).toFixed(3)) : 0,
        avgLatencyMs: s.count > 0 ? Math.round(s.totalLatencyMs / s.count) : 0,
        maxLatencyMs: s.maxLatencyMs,
      };
    }
    return endpoints;
  }
}

const store: MetricsStore = new InMemoryMetricsStore();

export function recordRequest(endpoint: string, latencyMs: number, isError: boolean): void {
  store.record(endpoint, latencyMs, isError);
}

export function getMetricsSnapshot(): Record<string, unknown> {
  return store.snapshot();
}

/**
 * Every route handler logs a one-line summary and records it into the metrics map — this pairs
 * those two calls so route handlers don't repeat the pairing themselves (the log line's *content*
 * is still route-specific and built by the caller; only the "log it, then record it" boilerplate
 * is centralized here).
 */
export function logAndRecord(logLine: string, endpoint: string, latencyMs: number, isError: boolean): void {
  console.log(logLine);
  recordRequest(endpoint, latencyMs, isError);
}
