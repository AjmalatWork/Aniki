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

export class EnrichmentError extends Error {}

/** Thrown when the daily Gemini call cap is reached; caught separately to return 429 instead of 422. */
export class QuotaExceededError extends Error {}
