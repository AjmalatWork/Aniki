import { JSDOM } from "jsdom";
import { Readability } from "@mozilla/readability";
import { EnrichmentError, type ExtractedContent } from "../types.js";

const FETCH_TIMEOUT_MS = 15_000;
const MIN_READABLE_LENGTH = 200;

function metaContent(document: Document, property: string): string | null {
  const el =
    document.querySelector(`meta[property="${property}"]`) ??
    document.querySelector(`meta[name="${property}"]`);
  return el?.getAttribute("content")?.trim() || null;
}

export async function extractArticle(url: string): Promise<ExtractedContent> {
  let html: string;
  try {
    const res = await fetch(url, {
      headers: {
        "User-Agent":
          "Mozilla/5.0 (compatible; AnikiBot/1.0; +https://aniki.app)",
        Accept: "text/html,application/xhtml+xml",
      },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (!res.ok) {
      throw new EnrichmentError(`Fetch failed with status ${res.status}`);
    }
    html = await res.text();
  } catch (err) {
    if (err instanceof EnrichmentError) throw err;
    throw new EnrichmentError(
      `Could not fetch article URL: ${(err as Error).message}`
    );
  }

  const dom = new JSDOM(html, { url });
  const document = dom.window.document;

  // Read OpenGraph metadata before Readability rewrites the DOM.
  const ogTitle = metaContent(document, "og:title");
  const ogDescription = metaContent(document, "og:description");
  const ogImage = metaContent(document, "og:image");

  let content: string | null = null;
  let title: string | null = null;

  try {
    const article = new Readability(document).parse();
    if (article?.textContent && article.textContent.trim().length >= MIN_READABLE_LENGTH) {
      content = article.textContent.trim();
      title = article.title?.trim() || ogTitle;
    }
  } catch {
    // Readability can throw on malformed markup; fall through to the OG fallback below.
  }

  if (!content) {
    const fallback = [ogTitle, ogDescription].filter(Boolean).join("\n\n").trim();
    if (!fallback) {
      throw new EnrichmentError(
        "Article is unparseable and has no OpenGraph fallback (likely paywalled or blocked)"
      );
    }
    content = fallback;
    title = ogTitle;
  }

  return { content, title, thumbnailUrl: ogImage };
}
