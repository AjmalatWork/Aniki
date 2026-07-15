import { createHash } from "node:crypto";
import type { LlmEnrichment } from "./types.js";

// In-memory only: resets on every server restart. A persistent cache
// (Redis/DB) is out of scope for this slice.
const cache = new Map<string, LlmEnrichment>();

export function hashContent(content: string): string {
  return createHash("sha256").update(content).digest("hex");
}

export function getCached(hash: string): LlmEnrichment | undefined {
  return cache.get(hash);
}

export function setCached(hash: string, result: LlmEnrichment): void {
  cache.set(hash, result);
}
