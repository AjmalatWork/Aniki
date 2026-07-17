import { JSDOM } from "jsdom";
import { Readability } from "@mozilla/readability";
import { assertSafeUrl } from "../security/urlGuard.js";
import { EnrichmentError, ExtractionFailedError, FetchFailedError, type ExtractedContent } from "../types.js";

const FETCH_TIMEOUT_MS = 15_000;
const MIN_READABLE_LENGTH = 200;
// A HEAD response reporting fewer bytes than this is almost certainly a 1x1 tracking pixel, not
// a usable thumbnail -- can't decode actual pixel dimensions without downloading the image, so
// this is a cheap heuristic rather than true dimension sniffing.
const MIN_IMAGE_CONTENT_LENGTH_BYTES = 200;

function metaContent(document: Document, property: string): string | null {
  const el =
    document.querySelector(`meta[property="${property}"]`) ??
    document.querySelector(`meta[name="${property}"]`);
  return el?.getAttribute("content")?.trim() || null;
}

/**
 * Resolves an og:image/twitter:image candidate into a usable, safe, real-image thumbnail URL, or
 * null if it isn't one. Never throws -- a bad/unreachable candidate image should degrade to "no
 * thumbnail" (the client's monogram-tile fallback), not fail the whole enrichment.
 */
async function resolveThumbnail(candidate: string | null, baseUrl: string): Promise<string | null> {
  if (!candidate || candidate.startsWith("data:")) return null;

  let resolved: string;
  try {
    resolved = new URL(candidate, baseUrl).toString();
  } catch {
    return null;
  }

  try {
    await assertSafeUrl(resolved);
  } catch {
    return null; // same SSRF guard as the article fetch itself -- the image host is untrusted too
  }

  try {
    const res = await fetch(resolved, { method: "HEAD", signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    if (!res.ok) return null;
    const contentType = res.headers.get("content-type") ?? "";
    if (!contentType.startsWith("image/")) return null;
    const contentLength = Number(res.headers.get("content-length"));
    if (Number.isFinite(contentLength) && contentLength > 0 && contentLength < MIN_IMAGE_CONTENT_LENGTH_BYTES) {
      return null;
    }
    return resolved;
  } catch {
    return null; // network hiccup on the image -- not fatal to the article enrichment
  }
}

export async function extractArticle(url: string): Promise<ExtractedContent> {
  await assertSafeUrl(url);

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
      throw new FetchFailedError(`Fetch failed with status ${res.status}`);
    }
    // fetch() follows redirects by default; the pre-check above only validated the URL the
    // client gave us, not wherever a redirect chain actually landed. Check the final URL too,
    // as defense in depth against a public-looking URL redirecting to an internal address.
    if (res.url && res.url !== url) {
      await assertSafeUrl(res.url);
    }
    html = await res.text();
  } catch (err) {
    // Any EnrichmentError here (FetchFailedError above, or an SSRF rejection from the redirect
    // recheck) already carries its own code/message -- only a raw fetch()/timeout exception needs
    // wrapping into a FetchFailedError.
    if (err instanceof EnrichmentError) throw err;
    throw new FetchFailedError(
      `Could not fetch article URL: ${(err as Error).message}`
    );
  }

  const dom = new JSDOM(html, { url });
  const document = dom.window.document;

  // Read OpenGraph metadata before Readability rewrites the DOM.
  const ogTitle = metaContent(document, "og:title");
  const ogDescription = metaContent(document, "og:description");
  const ogImageCandidate = metaContent(document, "og:image") ?? metaContent(document, "twitter:image");
  const thumbnailUrl = await resolveThumbnail(ogImageCandidate, url);

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
      throw new ExtractionFailedError(
        "Article is unparseable and has no OpenGraph fallback (likely paywalled or blocked)"
      );
    }
    content = fallback;
    title = ogTitle;
  }

  return { content, title, thumbnailUrl };
}
