import assert from "node:assert/strict";
import { test } from "node:test";
import { decideUpsert, itemContentEqual, itemTagContentEqual, tagContentEqual } from "./mergeLogic.js";
import type { ItemDto, ItemTagDto, PulledItemTagDto, PulledTagDto, TagDto } from "./types.js";

// -----------------------------------------------------------------
// decideUpsert: the LWW ("newer/equal updatedAt wins") + content-equality-skip decision that
// upsertItem/upsertExistingTagRow/upsertItemTag in repo.ts all delegate to.
// -----------------------------------------------------------------

test("decideUpsert: incoming strictly older than stored -> KEEP_STORED", () => {
  assert.equal(decideUpsert(100, 200, false), "KEEP_STORED");
});

test("decideUpsert: incoming strictly older than stored, even if content would be equal -> KEEP_STORED", () => {
  // Older wins nothing, regardless of content -- KEEP_STORED must be checked before content equality.
  assert.equal(decideUpsert(100, 200, true), "KEEP_STORED");
});

test("decideUpsert: equal updatedAt and identical content -> NO_OP (byte-identical retry)", () => {
  assert.equal(decideUpsert(200, 200, true), "NO_OP");
});

test("decideUpsert: equal updatedAt but different content -> OVERWRITE", () => {
  assert.equal(decideUpsert(200, 200, false), "OVERWRITE");
});

test("decideUpsert: incoming strictly newer with different content -> OVERWRITE", () => {
  assert.equal(decideUpsert(300, 200, false), "OVERWRITE");
});

test("decideUpsert: content-equal check is checked before newer-wins, per the real callers' contract", () => {
  // In practice a caller's contentEqual flag (itemContentEqual/tagContentEqual/itemTagContentEqual)
  // can only be true when updatedAt already matches, so "newer AND contentEqual" never happens from
  // a real caller -- but the function's literal contract (matching the original inline logic this
  // was extracted from) checks content-equality before falling through to OVERWRITE, so it still
  // resolves to NO_OP here rather than OVERWRITE.
  assert.equal(decideUpsert(300, 200, true), "NO_OP");
});

// -----------------------------------------------------------------
// itemContentEqual
// -----------------------------------------------------------------

function baseItem(overrides: Partial<ItemDto> = {}): ItemDto {
  return {
    id: "item-1",
    type: "WEB_ARTICLE",
    sourceUrl: "https://example.com",
    normalizedUrl: "example.com",
    title: "Title",
    bodyText: "Body",
    summary: "Summary",
    thumbnailUrl: null,
    category: "Tech",
    entities: { people: [], places: [], dates: [] },
    eventDate: null,
    status: "ENRICHED",
    isStarred: false,
    summaryLocked: false,
    tagsLocked: false,
    updatedAt: 1000,
    deletedAt: null,
    ...overrides,
  };
}

test("itemContentEqual: identical items are equal", () => {
  assert.equal(itemContentEqual(baseItem(), baseItem()), true);
});

test("itemContentEqual: differing title is not equal", () => {
  assert.equal(itemContentEqual(baseItem(), baseItem({ title: "Different" })), false);
});

test("itemContentEqual: differing entities (nested object) is not equal", () => {
  assert.equal(
    itemContentEqual(baseItem(), baseItem({ entities: { people: ["Ada"], places: [], dates: [] } })),
    false
  );
});

test("itemContentEqual: differing deletedAt (tombstone state) is not equal", () => {
  assert.equal(itemContentEqual(baseItem(), baseItem({ deletedAt: 5000 })), false);
});

test("itemContentEqual: differing updatedAt is not equal", () => {
  assert.equal(itemContentEqual(baseItem(), baseItem({ updatedAt: 2000 })), false);
});

// -----------------------------------------------------------------
// tagContentEqual
// -----------------------------------------------------------------

function baseTag(overrides: Partial<TagDto> = {}): TagDto {
  return { id: "tag-1", label: "kotlin", origin: "USER", updatedAt: 1000, deletedAt: null, ...overrides };
}

function basePulledTag(overrides: Partial<PulledTagDto> = {}): PulledTagDto {
  return { ...baseTag(), seq: 1, ...overrides };
}

test("tagContentEqual: identical tags are equal (seq is not part of content)", () => {
  assert.equal(tagContentEqual(baseTag(), basePulledTag({ seq: 42 })), true);
});

test("tagContentEqual: differing label is not equal", () => {
  assert.equal(tagContentEqual(baseTag(), basePulledTag({ label: "kotlin-coroutines" })), false);
});

test("tagContentEqual: differing origin is not equal", () => {
  assert.equal(tagContentEqual(baseTag({ origin: "AI" }), basePulledTag({ origin: "USER" })), false);
});

// -----------------------------------------------------------------
// itemTagContentEqual
// -----------------------------------------------------------------

function baseItemTag(overrides: Partial<ItemTagDto> = {}): ItemTagDto {
  return { itemId: "item-1", tagId: "tag-1", updatedAt: 1000, deletedAt: null, ...overrides };
}

function basePulledItemTag(overrides: Partial<PulledItemTagDto> = {}): PulledItemTagDto {
  return { ...baseItemTag(), seq: 1, ...overrides };
}

test("itemTagContentEqual: identical links are equal", () => {
  assert.equal(itemTagContentEqual(baseItemTag(), basePulledItemTag()), true);
});

test("itemTagContentEqual: differing deletedAt (link tombstoned) is not equal", () => {
  assert.equal(itemTagContentEqual(baseItemTag(), basePulledItemTag({ deletedAt: 3000 })), false);
});

// -----------------------------------------------------------------
// End-to-end sanity: a byte-identical retried push must be a true NO_OP for every row kind.
// This is the exact scenario the doc comment on decideUpsert callers describes: "a byte-identical
// retry (e.g. after an ambiguous network failure) never bumps seq or touches synced_at."
// -----------------------------------------------------------------

test("identical retry push is NO_OP for items, tags, and item_tags alike", () => {
  const item = baseItem();
  assert.equal(decideUpsert(item.updatedAt, item.updatedAt, itemContentEqual(item, item)), "NO_OP");

  const tag = baseTag();
  const pulledTag = basePulledTag(tag);
  assert.equal(decideUpsert(tag.updatedAt, pulledTag.updatedAt, tagContentEqual(tag, pulledTag)), "NO_OP");

  const itemTag = baseItemTag();
  const pulledItemTag = basePulledItemTag(itemTag);
  assert.equal(
    decideUpsert(itemTag.updatedAt, pulledItemTag.updatedAt, itemTagContentEqual(itemTag, pulledItemTag)),
    "NO_OP"
  );
});
