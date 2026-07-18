import assert from "node:assert/strict";
import { test } from "node:test";
import { exceedsPushRowCap, pushRowCount } from "./pushLimits.js";
import type { PushRequest } from "./types.js";

function requestWith(counts: { items?: number; tags?: number; itemTags?: number; engagementEvents?: number }): PushRequest {
  return {
    items: new Array(counts.items ?? 0).fill(null),
    tags: new Array(counts.tags ?? 0).fill(null),
    itemTags: new Array(counts.itemTags ?? 0).fill(null),
    engagementEvents: new Array(counts.engagementEvents ?? 0).fill(null),
  } as unknown as PushRequest;
}

test("pushRowCount sums across all four row kinds", () => {
  assert.equal(pushRowCount(requestWith({ items: 3, tags: 1, itemTags: 2, engagementEvents: 5 })), 11);
});

test("pushRowCount of an empty request is zero", () => {
  assert.equal(pushRowCount(requestWith({})), 0);
});

test("exceedsPushRowCap: exactly at the cap is not over it", () => {
  assert.equal(exceedsPushRowCap(requestWith({ items: 500 }), 500), false);
});

test("exceedsPushRowCap: one row over the cap is over it", () => {
  assert.equal(exceedsPushRowCap(requestWith({ items: 501 }), 500), true);
});

test("exceedsPushRowCap: the cap counts the sum across all four kinds, not any one alone", () => {
  // 200 + 200 + 200 + 200 = 800, over a 500 cap, even though no single kind alone exceeds it.
  assert.equal(
    exceedsPushRowCap(requestWith({ items: 200, tags: 200, itemTags: 200, engagementEvents: 200 }), 500),
    true
  );
});
