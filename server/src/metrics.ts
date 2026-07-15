/**
 * In-memory per-endpoint counters and latency stats — enough to answer "is this working"
 * without a debugger or a metrics service (Slice 6 observability task). Resets on server
 * restart; not persisted, not exported anywhere.
 */
interface EndpointStats {
  count: number;
  errors: number;
  totalLatencyMs: number;
  maxLatencyMs: number;
}

const stats = new Map<string, EndpointStats>();

export function recordRequest(endpoint: string, latencyMs: number, isError: boolean): void {
  const existing = stats.get(endpoint) ?? { count: 0, errors: 0, totalLatencyMs: 0, maxLatencyMs: 0 };
  existing.count += 1;
  if (isError) existing.errors += 1;
  existing.totalLatencyMs += latencyMs;
  existing.maxLatencyMs = Math.max(existing.maxLatencyMs, latencyMs);
  stats.set(endpoint, existing);
}

export function getMetricsSnapshot(): Record<string, unknown> {
  const endpoints: Record<string, unknown> = {};
  for (const [endpoint, s] of stats.entries()) {
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
