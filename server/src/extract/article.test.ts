import assert from "node:assert/strict";
import { afterEach, beforeEach, mock, test } from "node:test";
import { EnrichmentError } from "../types.js";

// assertSafeUrl (called by extractArticle before fetching) does a real DNS lookup -- mocked here
// so tests don't depend on real network/DNS. Defaults to a public-looking address; individual
// SSRF tests override this per-call via dnsLookupImpl.
let dnsLookupImpl: (hostname: string) => Promise<{ address: string; family: number }[]> = async () => [
  { address: "93.184.216.34", family: 4 },
];

mock.module("node:dns/promises", {
  namedExports: {
    lookup: async (hostname: string, ..._rest: unknown[]) => dnsLookupImpl(hostname),
  },
});

const { extractArticle } = await import("./article.js");

const originalFetch = globalThis.fetch;

function htmlResponse(html: string, ok = true, status = 200, finalUrl?: string): Response {
  return {
    ok,
    status,
    url: finalUrl ?? "",
    text: async () => html,
  } as Response;
}

/** Default HEAD response for a real, normal-sized JPEG -- what resolveThumbnail expects to see
 *  for a usable thumbnail candidate. */
function imageHeadResponse(
  ok = true,
  contentType = "image/jpeg",
  contentLength = "50000"
): Response {
  return {
    ok,
    status: ok ? 200 : 404,
    headers: new Headers({ "content-type": contentType, "content-length": contentLength }),
  } as Response;
}

/** Routes GET (the article page) vs HEAD (a thumbnail candidate) to separate handlers, since both
 *  now go through the same global fetch stub. */
function stubFetch(handlers: { get?: typeof fetch; head?: typeof fetch }): void {
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    if (method === "HEAD") {
      if (!handlers.head) throw new Error("HEAD not stubbed for this test");
      return handlers.head(input, init);
    }
    if (!handlers.get) throw new Error("GET not stubbed for this test");
    return handlers.get(input, init);
  }) as typeof globalThis.fetch;
}

beforeEach(() => {
  stubFetch({
    get: async () => {
      throw new Error("fetch not stubbed for this test");
    },
  });
  dnsLookupImpl = async () => [{ address: "93.184.216.34", family: 4 }];
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

const longParagraph = "This is a real article paragraph. ".repeat(20); // well over 200 chars

test("extractArticle: readable page uses Readability content and title", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <title>Real Title</title>
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.title, "Real Title");
  assert.ok(result.content.includes("real article paragraph"));
});

test("extractArticle: content under MIN_READABLE_LENGTH falls back to OG title+description", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:title" content="OG Title">
          <meta property="og:description" content="OG description text.">
        </head><body>
          <article><p>Too short.</p></article>
        </body></html>
      `),
  });

  const result = await extractArticle("https://example.com/thin-article");

  assert.equal(result.title, "OG Title");
  assert.equal(result.content, "OG Title\n\nOG description text.");
});

test("extractArticle: unparseable page with no OG fallback throws EnrichmentError (paywall/blocked case)", async () => {
  stubFetch({ get: async () => htmlResponse("<html><body><p>Too short.</p></body></html>") });

  await assert.rejects(
    () => extractArticle("https://example.com/paywalled"),
    (err: unknown) => err instanceof EnrichmentError
  );
});

test("extractArticle: non-ok HTTP response throws EnrichmentError with the status", async () => {
  stubFetch({ get: async () => htmlResponse("", false, 404) });

  await assert.rejects(
    () => extractArticle("https://example.com/missing"),
    (err: unknown) => err instanceof EnrichmentError && /404/.test(err.message)
  );
});

test("extractArticle: network failure is wrapped in an EnrichmentError, not a raw exception", async () => {
  stubFetch({
    get: async () => {
      throw new TypeError("fetch failed: getaddrinfo ENOTFOUND");
    },
  });

  await assert.rejects(
    () => extractArticle("https://unreachable.example.com/"),
    (err: unknown) => err instanceof EnrichmentError && /Could not fetch article URL/.test(err.message)
  );
});

test("extractArticle: a normal public URL is allowed through", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head><title>Real Title</title></head>
        <body><article><h1>Real Title</h1><p>${longParagraph}</p></article></body></html>
      `),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.title, "Real Title");
});

test("extractArticle: a redirect landing on a private address is rejected even though the original URL was safe", async () => {
  stubFetch({ get: async () => htmlResponse("<html></html>", true, 200, "http://169.254.169.254/latest/meta-data/") });

  await assert.rejects(
    () => extractArticle("https://example.com/redirects-away"),
    (err: unknown) => err instanceof EnrichmentError && /private\/internal/.test(err.message)
  );
});

// -----------------------------------------------------------------
// SSRF guard (SEC1)
// -----------------------------------------------------------------

test("extractArticle: non-http(s) scheme is rejected before any fetch happens", async () => {
  let fetchCalled = false;
  stubFetch({
    get: async () => {
      fetchCalled = true;
      return htmlResponse("<html></html>");
    },
  });

  await assert.rejects(
    () => extractArticle("file:///etc/passwd"),
    (err: unknown) => err instanceof EnrichmentError
  );
  assert.equal(fetchCalled, false);
});

test("extractArticle: a literal loopback IP is rejected", async () => {
  await assert.rejects(
    () => extractArticle("http://127.0.0.1:8080/admin"),
    (err: unknown) => err instanceof EnrichmentError && /private\/internal/.test(err.message)
  );
});

test("extractArticle: the cloud metadata address is rejected", async () => {
  await assert.rejects(
    () => extractArticle("http://169.254.169.254/latest/meta-data/"),
    (err: unknown) => err instanceof EnrichmentError && /private\/internal/.test(err.message)
  );
});

test("extractArticle: 'localhost' is rejected", async () => {
  await assert.rejects(
    () => extractArticle("http://localhost:4000/sync"),
    (err: unknown) => err instanceof EnrichmentError
  );
});

test("extractArticle: a public-looking hostname whose DNS resolves to a private IP is rejected", async () => {
  dnsLookupImpl = async () => [{ address: "10.0.0.5", family: 4 }];

  await assert.rejects(
    () => extractArticle("https://looks-public.example.com/"),
    (err: unknown) => err instanceof EnrichmentError && /private\/internal/.test(err.message)
  );
});

// -----------------------------------------------------------------
// OG/twitter:image thumbnail resolution (#2)
// -----------------------------------------------------------------

test("extractArticle: a valid og:image resolves to thumbnailUrl", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:title" content="OG Title">
          <meta property="og:image" content="https://example.com/thumb.jpg">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, "https://example.com/thumb.jpg");
});

test("extractArticle: falls back to twitter:image when og:image is absent", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta name="twitter:image" content="https://example.com/twitter-thumb.jpg">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, "https://example.com/twitter-thumb.jpg");
});

test("extractArticle: a relative og:image is resolved against the page's base URL", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="/images/thumb.jpg">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(),
  });

  const result = await extractArticle("https://example.com/articles/some-post");

  assert.equal(result.thumbnailUrl, "https://example.com/images/thumb.jpg");
});

test("extractArticle: a data: URI og:image is ignored, not resolved", async () => {
  let headCalled = false;
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="data:image/gif;base64,R0lGODlh">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => {
      headCalled = true;
      return imageHeadResponse();
    },
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
  assert.equal(headCalled, false);
});

test("extractArticle: a non-image content-type is rejected", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="https://example.com/not-an-image">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(true, "text/html", "5000"),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
});

test("extractArticle: a tiny content-length (likely tracking pixel) is rejected", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="https://example.com/pixel.gif">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(true, "image/gif", "43"),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
});

test("extractArticle: a HEAD request that fails outright degrades to no thumbnail, not a failed enrichment", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="https://example.com/dead-image.jpg">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => imageHeadResponse(false),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
  assert.ok(result.content.includes("real article paragraph")); // rest of enrichment still succeeded
});

test("extractArticle: an og:image resolving to a private address is rejected (SSRF guard reused)", async () => {
  let headCalled = false;
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head>
          <meta property="og:image" content="http://169.254.169.254/fake-thumb.jpg">
        </head><body>
          <article><h1>Real Title</h1><p>${longParagraph}</p></article>
        </body></html>
      `),
    head: async () => {
      headCalled = true;
      return imageHeadResponse();
    },
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
  assert.equal(headCalled, false); // rejected before ever fetching the image
});

test("extractArticle: no og:image or twitter:image at all yields a null thumbnailUrl, not an error", async () => {
  stubFetch({
    get: async () =>
      htmlResponse(`
        <html><head><title>Real Title</title></head>
        <body><article><h1>Real Title</h1><p>${longParagraph}</p></article></body></html>
      `),
  });

  const result = await extractArticle("https://example.com/article");

  assert.equal(result.thumbnailUrl, null);
});
