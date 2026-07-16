import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { extractArticle } from "./article.js";
import { EnrichmentError } from "../types.js";

const originalFetch = globalThis.fetch;

function stubFetch(impl: typeof fetch): void {
  globalThis.fetch = impl as typeof globalThis.fetch;
}

function htmlResponse(html: string, ok = true, status = 200): Response {
  return {
    ok,
    status,
    text: async () => html,
  } as Response;
}

beforeEach(() => {
  stubFetch(async () => {
    throw new Error("fetch not stubbed for this test");
  });
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

const longParagraph = "This is a real article paragraph. ".repeat(20); // well over 200 chars

test("extractArticle: readable page uses Readability content and title", async () => {
  stubFetch(async () =>
    htmlResponse(`
      <html><head>
        <title>Real Title</title>
      </head><body>
        <article><h1>Real Title</h1><p>${longParagraph}</p></article>
      </body></html>
    `)
  );

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.title, "Real Title");
  assert.ok(result.content.includes("real article paragraph"));
});

test("extractArticle: content under MIN_READABLE_LENGTH falls back to OG title+description", async () => {
  stubFetch(async () =>
    htmlResponse(`
      <html><head>
        <meta property="og:title" content="OG Title">
        <meta property="og:description" content="OG description text.">
      </head><body>
        <article><p>Too short.</p></article>
      </body></html>
    `)
  );

  const result = await extractArticle("https://example.com/thin-article");

  assert.equal(result.title, "OG Title");
  assert.equal(result.content, "OG Title\n\nOG description text.");
});

test("extractArticle: unparseable page with no OG fallback throws EnrichmentError (paywall/blocked case)", async () => {
  stubFetch(async () => htmlResponse("<html><body><p>Too short.</p></body></html>"));

  await assert.rejects(
    () => extractArticle("https://example.com/paywalled"),
    (err: unknown) => err instanceof EnrichmentError
  );
});

test("extractArticle: non-ok HTTP response throws EnrichmentError with the status", async () => {
  stubFetch(async () => htmlResponse("", false, 404));

  await assert.rejects(
    () => extractArticle("https://example.com/missing"),
    (err: unknown) => err instanceof EnrichmentError && /404/.test(err.message)
  );
});

test("extractArticle: network failure is wrapped in an EnrichmentError, not a raw exception", async () => {
  stubFetch(async () => {
    throw new TypeError("fetch failed: getaddrinfo ENOTFOUND");
  });

  await assert.rejects(
    () => extractArticle("https://unreachable.example.com/"),
    (err: unknown) => err instanceof EnrichmentError && /Could not fetch article URL/.test(err.message)
  );
});

test("extractArticle: og:image is captured as thumbnailUrl even on the Readability-success path", async () => {
  stubFetch(async () =>
    htmlResponse(`
      <html><head>
        <meta property="og:title" content="OG Title">
        <meta property="og:image" content="https://example.com/thumb.jpg">
      </head><body>
        <article><h1>Real Title</h1><p>${longParagraph}</p></article>
      </body></html>
    `)
  );

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, "https://example.com/thumb.jpg");
});
