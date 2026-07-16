import assert from "node:assert/strict";
import { mock, test } from "node:test";
import { QuotaExceededError } from "./types.js";

// gemini.ts instantiates a real GoogleGenAI client at module load time, so the SDK is mocked
// before the module under test is imported (mock.module() can only run once per module per
// process -- see the youtube.test.ts comment for the same pattern).
let generateContentImpl: () => Promise<{ text: string | undefined }> = async () => ({
  text: JSON.stringify({ summary: "s", category: "Tech", tags: [], entities: { people: [], places: [], dates: [] }, eventDate: null }),
});

mock.module("@google/genai", {
  namedExports: {
    GoogleGenAI: class {
      models = {
        generateContent: async (..._args: unknown[]) => generateContentImpl(),
      };
    },
    Type: { OBJECT: "OBJECT", STRING: "STRING", ARRAY: "ARRAY" },
  },
});

const { enrichWithGemini } = await import("./gemini.js");

test("enrichWithGemini: success on the first attempt consumes exactly one call", async () => {
  generateContentImpl = async () => ({
    text: JSON.stringify({
      title: "T",
      summary: "s",
      category: "Tech",
      tags: ["a"],
      entities: { people: [], places: [], dates: [] },
      eventDate: null,
    }),
  });
  let consumeCount = 0;
  const consumeCall = () => {
    consumeCount += 1;
    return true;
  };

  const result = await enrichWithGemini("NOTE", "content", consumeCall);

  assert.equal(consumeCount, 1);
  assert.equal(result.summary, "s");
});

test("enrichWithGemini: a failed first attempt that succeeds on retry consumes two calls", async () => {
  let callNumber = 0;
  generateContentImpl = async () => {
    callNumber += 1;
    if (callNumber === 1) return { text: undefined }; // triggers "Empty response from Gemini"
    return {
      text: JSON.stringify({
        summary: "s",
        category: "Tech",
        tags: [],
        entities: { people: [], places: [], dates: [] },
        eventDate: null,
      }),
    };
  };
  let consumeCount = 0;
  const consumeCall = () => {
    consumeCount += 1;
    return true;
  };

  const result = await enrichWithGemini("NOTE", "content", consumeCall);

  // This is the exact bug R2 fixes: a retried call is a second real, billable Gemini API call
  // and must be checked against the cap too, not just the first attempt.
  assert.equal(consumeCount, 2);
  assert.equal(result.summary, "s");
});

test("enrichWithGemini: cap exhausted before the first attempt throws QuotaExceededError without calling the model", async () => {
  let modelCalled = false;
  generateContentImpl = async () => {
    modelCalled = true;
    return { text: "{}" };
  };

  await assert.rejects(
    () => enrichWithGemini("NOTE", "content", () => false),
    (err: unknown) => err instanceof QuotaExceededError
  );
  assert.equal(modelCalled, false);
});

test("enrichWithGemini: cap exhausted between the first (failed) attempt and the retry throws QuotaExceededError", async () => {
  generateContentImpl = async () => ({ text: undefined }); // always fails
  let consumeCount = 0;
  const consumeCall = () => {
    consumeCount += 1;
    return consumeCount === 1; // allow the first attempt, deny the retry
  };

  await assert.rejects(
    () => enrichWithGemini("NOTE", "content", consumeCall),
    (err: unknown) => err instanceof QuotaExceededError
  );
  assert.equal(consumeCount, 2);
});

test("enrichWithGemini: both attempts failing (not quota-related) throws EnrichmentError, not QuotaExceededError", async () => {
  generateContentImpl = async () => ({ text: undefined });

  await assert.rejects(() => enrichWithGemini("NOTE", "content", () => true), (err: unknown) => {
    return err instanceof Error && !(err instanceof QuotaExceededError) && /failed twice/.test(err.message);
  });
});
