export type ItemType = "WEB_ARTICLE" | "YOUTUBE_VIDEO" | "NOTE";

export interface EnrichRequest {
  id: string;
  type: ItemType;
  sourceUrl: string | null;
  bodyText: string | null;
}

export interface EnrichmentEntities {
  people: string[];
  places: string[];
  dates: string[];
}

export interface EnrichResponse {
  id: string;
  title: string | null;
  summary: string;
  category: string;
  tags: string[];
  entities: EnrichmentEntities;
  thumbnailUrl: string | null;
  eventDate: string | null;
}

/** What the LLM itself returns; thumbnailUrl/title-from-extraction are merged in separately. */
export interface LlmEnrichment {
  title: string | null;
  summary: string;
  category: string;
  tags: string[];
  entities: EnrichmentEntities;
  eventDate: string | null;
}

export interface ExtractedContent {
  /** Text handed to the LLM (article body, video title+transcript, or note body). */
  content: string;
  /** A better title than what the client sent, if extraction found one. */
  title: string | null;
  thumbnailUrl: string | null;
}

export interface ExtractThumbnailRequest {
  sourceUrl: string;
}

export interface ExtractThumbnailResponse {
  thumbnailUrl: string | null;
}

/** Machine-readable failure category, surfaced to the client alongside `message` so it can persist
 *  and display the real per-failure copy instead of a single generic string, and (for
 *  QUOTA_EXCEEDED specifically) skip its normal rapid retry -- see EnrichmentWorker.kt. */
export type EnrichmentErrorCode =
  | "RATE_LIMITED"
  | "QUOTA_EXCEEDED"
  | "FETCH_FAILED"
  | "EXTRACTION_FAILED"
  | "GENERIC";

/**
 * Runtime mirror of the EnrichmentErrorCode union above (M3 of the maintainability audit): a TS
 * `type` has no representation at runtime, so there's nothing for a test -- on either side of the
 * client/server boundary -- to enumerate/verify against without one. This array is the thing
 * types.test.ts checks against every code actually thrown/returned server-side, and is also what
 * the client's EnrichmentMessagesTest.kt hand-mirrors to verify every code has curated copy and a
 * retry-eligibility entry. Keep this in lockstep with the union above when either changes.
 */
export const ENRICHMENT_ERROR_CODES: readonly EnrichmentErrorCode[] = [
  "RATE_LIMITED",
  "QUOTA_EXCEEDED",
  "FETCH_FAILED",
  "EXTRACTION_FAILED",
  "GENERIC",
];

export class EnrichmentError extends Error {
  readonly code: EnrichmentErrorCode = "GENERIC";
}

/** The remote fetch itself failed -- a non-OK HTTP status or a network-level error reaching the
 *  source URL (article page or YouTube's oEmbed endpoint). */
export class FetchFailedError extends EnrichmentError {
  override readonly code: EnrichmentErrorCode = "FETCH_FAILED";
}

/** The fetch succeeded but the page couldn't be turned into usable content -- unparseable markup
 *  with no OpenGraph fallback (typically paywalled or blocked). */
export class ExtractionFailedError extends EnrichmentError {
  override readonly code: EnrichmentErrorCode = "EXTRACTION_FAILED";
}

/** Thrown when the daily Gemini call cap is reached; caught separately to return 429 instead of 422. */
export class QuotaExceededError extends Error {}
