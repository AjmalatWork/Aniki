import assert from "node:assert/strict";
import { test } from "node:test";
import { isNoteBodyTooLong } from "./enrichLimits.js";

test("isNoteBodyTooLong: a NOTE body under the cap is fine", () => {
  assert.equal(isNoteBodyTooLong("NOTE", "a".repeat(100), 50_000), false);
});

test("isNoteBodyTooLong: a NOTE body exactly at the cap is fine", () => {
  assert.equal(isNoteBodyTooLong("NOTE", "a".repeat(50_000), 50_000), false);
});

test("isNoteBodyTooLong: a NOTE body one char over the cap is too long", () => {
  assert.equal(isNoteBodyTooLong("NOTE", "a".repeat(50_001), 50_000), true);
});

test("isNoteBodyTooLong: a null NOTE body is not too long (nothing to bound)", () => {
  assert.equal(isNoteBodyTooLong("NOTE", null, 50_000), false);
});

test("isNoteBodyTooLong: a WEB_ARTICLE/YOUTUBE_VIDEO body is never checked, regardless of length", () => {
  assert.equal(isNoteBodyTooLong("WEB_ARTICLE", "a".repeat(1_000_000), 50_000), false);
  assert.equal(isNoteBodyTooLong("YOUTUBE_VIDEO", "a".repeat(1_000_000), 50_000), false);
});
