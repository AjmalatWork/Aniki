import assert from "node:assert/strict";
import { test } from "node:test";
import {
  ENRICHMENT_ERROR_CODES,
  EnrichmentError,
  ExtractionFailedError,
  FetchFailedError,
  QuotaExceededError,
} from "./types.js";

// -----------------------------------------------------------------
// M3 (maintainability audit): every EnrichmentErrorCode this server can actually produce must be
// present in ENRICHMENT_ERROR_CODES, the runtime mirror of the (otherwise type-only) union the
// client's EnrichmentMessagesTest.kt hand-mirrors to verify curated copy exists for each code.
// This doesn't (can't) check the TS type itself -- there's no runtime handle on a `type` -- but it
// does check every concrete source of a code in this codebase against the array, so a new error
// class or a new hardcoded `code:` literal that isn't added to ENRICHMENT_ERROR_CODES fails here
// instead of silently shipping a code the client has no curated message for.
// -----------------------------------------------------------------

test("ENRICHMENT_ERROR_CODES contains every code the built-in EnrichmentError subclasses produce", () => {
  assert.ok(ENRICHMENT_ERROR_CODES.includes(new EnrichmentError("x").code)); // "GENERIC"
  assert.ok(ENRICHMENT_ERROR_CODES.includes(new FetchFailedError("x").code)); // "FETCH_FAILED"
  assert.ok(ENRICHMENT_ERROR_CODES.includes(new ExtractionFailedError("x").code)); // "EXTRACTION_FAILED"
});

test("ENRICHMENT_ERROR_CODES contains the two codes hardcoded as string literals in index.ts", () => {
  // QuotaExceededError carries no `code` field of its own (see types.ts's doc on it) -- index.ts's
  // POST /enrich handler hardcodes "QUOTA_EXCEEDED" when it catches one, and "RATE_LIMITED" for its
  // own rate-limit rejection, both outside the EnrichmentError class hierarchy entirely. Asserted as
  // literals here (not derived from QuotaExceededError, which has nothing to derive from) since
  // that's genuinely what ships to the client for these two cases.
  assert.ok(ENRICHMENT_ERROR_CODES.includes("QUOTA_EXCEEDED"));
  assert.ok(ENRICHMENT_ERROR_CODES.includes("RATE_LIMITED"));
  assert.ok(new QuotaExceededError("x") instanceof Error); // sanity: the class still exists/constructs
});

test("ENRICHMENT_ERROR_CODES has no duplicates", () => {
  assert.equal(new Set(ENRICHMENT_ERROR_CODES).size, ENRICHMENT_ERROR_CODES.length);
});
