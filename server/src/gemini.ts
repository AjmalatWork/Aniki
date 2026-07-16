import { GoogleGenAI, Type } from "@google/genai";
import { config } from "./config.js";
import { EnrichmentError, QuotaExceededError, type ItemType, type LlmEnrichment } from "./types.js";

const CONTENT_CHAR_BUDGET = 12_000; // ~ a few thousand tokens; keeps this to one cheap call

const CATEGORIES = [
  "Tech",
  "Health",
  "Finance",
  "Learning",
  "Entertainment",
  "Personal",
  "News",
  "Other",
] as const;

const ai = new GoogleGenAI({ apiKey: config.geminiApiKey });

const responseSchema = {
  type: Type.OBJECT,
  properties: {
    title: { type: Type.STRING, nullable: true },
    summary: { type: Type.STRING },
    category: { type: Type.STRING, enum: [...CATEGORIES] },
    tags: { type: Type.ARRAY, items: { type: Type.STRING } },
    entities: {
      type: Type.OBJECT,
      properties: {
        people: { type: Type.ARRAY, items: { type: Type.STRING } },
        places: { type: Type.ARRAY, items: { type: Type.STRING } },
        dates: { type: Type.ARRAY, items: { type: Type.STRING } },
      },
      required: ["people", "places", "dates"],
    },
    eventDate: { type: Type.STRING, nullable: true },
  },
  required: ["summary", "category", "tags", "entities"],
};

function truncate(content: string): string {
  return content.length > CONTENT_CHAR_BUDGET
    ? content.slice(0, CONTENT_CHAR_BUDGET)
    : content;
}

function buildPrompt(type: ItemType, content: string): string {
  return `You are Aniki, an assistant that files away things a user saved so they can find them again later.

Given the ${type} content below, respond with strict JSON matching the provided schema:
- "title": a short, descriptive title (about 3-8 words), capturing the essence of the content in
  your own words -- not a restatement of the first line or sentence. Always provide one; only use
  null if the content is too sparse/unclear to title at all.
- "summary": 1-3 sentences, neutral tone, no marketing language.
- "category": exactly one of ${CATEGORIES.join(", ")}.
- "tags": 3-8 lowercase topical tags (single words or short phrases, no hashtags).
- "entities": named people, places, and any explicit dates mentioned (ISO-8601 where possible). Empty arrays if none.
- "eventDate": if the content clearly refers to a specific event/appointment/deadline date, return it as YYYY-MM-DD, else null.

Content:
"""
${content}
"""`;
}

async function callModel(prompt: string): Promise<LlmEnrichment> {
  const res = await ai.models.generateContent({
    model: config.geminiModel,
    contents: prompt,
    config: {
      responseMimeType: "application/json",
      responseSchema,
    },
  });

  const text = res.text;
  if (!text) {
    throw new EnrichmentError("Empty response from Gemini");
  }
  return JSON.parse(text) as LlmEnrichment;
}

/**
 * `consumeCall` gates the daily cap and must be checked before *every* real Gemini API call —
 * including the retry below, which is itself a second billable call. Checking it only once per
 * enrichWithGemini() (the original approach) let a failing-then-retrying request make 2 real API
 * calls while only counting 1 against the cap, undercounting by up to 2x.
 */
export async function enrichWithGemini(
  type: ItemType,
  content: string,
  consumeCall: () => boolean
): Promise<LlmEnrichment> {
  const prompt = buildPrompt(type, truncate(content));

  if (!consumeCall()) {
    throw new QuotaExceededError("Daily enrichment call limit reached — try again tomorrow");
  }
  try {
    return await callModel(prompt);
  } catch (firstErr) {
    // Malformed JSON (or a transient hiccup) — retry once before giving up.
    if (!consumeCall()) {
      throw new QuotaExceededError("Daily enrichment call limit reached — try again tomorrow");
    }
    try {
      return await callModel(prompt);
    } catch (secondErr) {
      throw new EnrichmentError(
        `Gemini call failed twice: ${(secondErr as Error).message}`
      );
    }
  }
}
