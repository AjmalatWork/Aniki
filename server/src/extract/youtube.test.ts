import assert from "node:assert/strict";
import { afterEach, beforeEach, mock, test } from "node:test";
import { EnrichmentError } from "../types.js";

const originalFetch = globalThis.fetch;

function stubFetch(impl: typeof fetch): void {
  globalThis.fetch = impl as typeof globalThis.fetch;
}

function oEmbedResponse(body: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => body,
  } as Response;
}

// mock.module() can only be called once per module per process, so the transcript behavior is
// swapped per-test via this mutable holder rather than re-mocking in each test.
let transcriptImpl: () => Promise<{ text: string }[]> = async () => [];

mock.module("youtube-transcript", {
  namedExports: {
    fetchTranscript: async (..._args: unknown[]) => transcriptImpl(),
  },
});

const { extractYouTube } = await import("./youtube.js");

beforeEach(() => {
  stubFetch(async () => {
    throw new Error("fetch not stubbed for this test");
  });
  transcriptImpl = async () => [];
});

afterEach(() => {
  globalThis.fetch = originalFetch;
});

test("extractYouTube: transcript available -> included in content", async () => {
  transcriptImpl = async () => [{ text: "Hello" }, { text: "world" }];
  stubFetch(async () =>
    oEmbedResponse({
      title: "A Video",
      author_name: "A Channel",
      thumbnail_url: "https://img.example/thumb.jpg",
    })
  );

  const result = await extractYouTube("https://youtube.com/watch?v=abc");

  assert.equal(result.title, "A Video");
  assert.equal(result.thumbnailUrl, "https://img.example/thumb.jpg");
  assert.ok(result.content.includes("Transcript:"));
  assert.ok(result.content.includes("Hello world"));
});

test("extractYouTube: transcript fetch throwing is non-fatal -- falls back to title+channel only", async () => {
  transcriptImpl = async () => {
    throw new Error("captions disabled for this video");
  };
  stubFetch(async () =>
    oEmbedResponse({
      title: "A Video",
      author_name: "A Channel",
      thumbnail_url: "https://img.example/thumb.jpg",
    })
  );

  const result = await extractYouTube("https://youtube.com/watch?v=abc");

  // Must not throw -- the whole point of T2 is that a missing/broken transcript never fails enrichment.
  assert.equal(result.title, "A Video");
  assert.ok(result.content.includes("No transcript available"));
  assert.ok(!result.content.includes("Transcript:\n"));
});

test("extractYouTube: empty transcript segments are treated the same as no transcript", async () => {
  transcriptImpl = async () => [{ text: "   " }];
  stubFetch(async () => oEmbedResponse({ title: "A Video", author_name: "A Channel", thumbnail_url: null }));

  const result = await extractYouTube("https://youtube.com/watch?v=abc");

  assert.ok(result.content.includes("No transcript available"));
});

test("extractYouTube: oEmbed failure (video removed/private) throws EnrichmentError", async () => {
  stubFetch(async () => oEmbedResponse(null, false, 404));

  await assert.rejects(
    () => extractYouTube("https://youtube.com/watch?v=removed"),
    (err: unknown) => err instanceof EnrichmentError && /404/.test(err.message)
  );
});
