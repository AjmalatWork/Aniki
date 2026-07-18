import assert from "node:assert/strict";
import { mock, test } from "node:test";

interface QueryCall {
  sql: string;
  params: unknown[];
}

interface FakeRow {
  user_id: string;
  seq: number;
  [key: string]: unknown;
}

let queryLog: QueryCall[] = [];
let rowCounts: { items: number; tags: number; itemTags: number; engagementEvents: number } = {
  items: 0,
  tags: 0,
  itemTags: 0,
  engagementEvents: 0,
};
let fakeTables: {
  items: FakeRow[];
  tags: FakeRow[];
  item_tags: FakeRow[];
  engagement_events: FakeRow[];
} = { items: [], tags: [], item_tags: [], engagement_events: [] };

function tableFor(sql: string): keyof typeof fakeTables | null {
  if (sql.includes("FROM item_tags")) return "item_tags";
  if (sql.includes("FROM items")) return "items";
  if (sql.includes("FROM tags")) return "tags";
  if (sql.includes("FROM engagement_events")) return "engagement_events";
  return null;
}

mock.module("../db/pool.js", {
  namedExports: {
    pool: {
      query: async (sql: string, params: unknown[] = []) => {
        queryLog.push({ sql, params });

        if (sql.trim().startsWith("DELETE")) {
          if (sql.includes("FROM item_tags")) return { rowCount: rowCounts.itemTags };
          if (sql.includes("FROM items")) return { rowCount: rowCounts.items };
          if (sql.includes("FROM tags")) return { rowCount: rowCounts.tags };
          if (sql.includes("FROM engagement_events")) return { rowCount: rowCounts.engagementEvents };
          return { rowCount: 0 };
        }

        if (sql.includes("all_changed_seqs")) {
          const [uid, since, limit] = params as [string, number, number];
          const allSeqs = [
            ...fakeTables.items,
            ...fakeTables.tags,
            ...fakeTables.item_tags,
            ...fakeTables.engagement_events,
          ]
            .filter((r) => r.user_id === uid && r.seq > since)
            .map((r) => r.seq)
            .sort((a, b) => a - b)
            .slice(0, limit);
          return { rowCount: allSeqs.length, rows: allSeqs.map((seq) => ({ seq })) };
        }

        const table = tableFor(sql);
        if (table) {
          const [uid, since, upper] = params as [string, number, number];
          const rows = fakeTables[table]
            .filter((r) => r.user_id === uid && r.seq > since && r.seq <= upper)
            .sort((a, b) => a.seq - b.seq);
          return { rowCount: rows.length, rows };
        }

        return { rowCount: 0, rows: [] };
      },
    },
  },
});

const { pullChanges, purgeOldTombstones } = await import("./repo.js");

function itemRow(seq: number, overrides: Partial<FakeRow> = {}): FakeRow {
  return {
    user_id: "u1",
    seq,
    id: `item-${seq}`,
    type: "NOTE",
    source_url: null,
    normalized_url: null,
    title: `Item ${seq}`,
    body_text: null,
    summary: null,
    thumbnail_url: null,
    category: null,
    entities: null,
    event_date: null,
    status: "ENRICHED",
    is_starred: false,
    summary_locked: false,
    tags_locked: false,
    updated_at: seq * 1000,
    deleted_at: null,
    ...overrides,
  };
}

function tagRow(seq: number, overrides: Partial<FakeRow> = {}): FakeRow {
  return {
    user_id: "u1",
    seq,
    id: `tag-${seq}`,
    label: `tag${seq}`,
    origin: "USER",
    updated_at: seq * 1000,
    deleted_at: null,
    ...overrides,
  };
}

// -----------------------------------------------------------------
// purgeOldTombstones
// -----------------------------------------------------------------

test("purgeOldTombstones: deletes from items, tags, item_tags, and engagement_events, and reports each count", async () => {
  queryLog = [];
  rowCounts = { items: 3, tags: 1, itemTags: 2, engagementEvents: 5 };

  const result = await purgeOldTombstones(90 * 24 * 60 * 60 * 1000);

  assert.deepEqual(result, { items: 3, tags: 1, itemTags: 2, engagementEvents: 5 });
  assert.equal(queryLog.length, 4);
  assert.ok(queryLog.some((c) => c.sql.includes("FROM items") && c.sql.includes("deleted_at IS NOT NULL")));
  assert.ok(queryLog.some((c) => c.sql.includes("FROM tags") && c.sql.includes("deleted_at IS NOT NULL")));
  assert.ok(queryLog.some((c) => c.sql.includes("FROM item_tags") && c.sql.includes("deleted_at IS NOT NULL")));
  assert.ok(queryLog.some((c) => c.sql.includes("FROM engagement_events")));
});

test("purgeOldTombstones: engagement_events purges by created_at, not deleted_at (it has no deleted_at column, S4)", async () => {
  queryLog = [];
  rowCounts = { items: 0, tags: 0, itemTags: 0, engagementEvents: 0 };

  await purgeOldTombstones(90 * 24 * 60 * 60 * 1000);

  const eventsCall = queryLog.find((c) => c.sql.includes("FROM engagement_events"));
  assert.ok(eventsCall, "expected a DELETE against engagement_events");
  assert.ok(eventsCall!.sql.includes("created_at"));
  assert.ok(!eventsCall!.sql.includes("deleted_at"));
});

test("purgeOldTombstones: cutoff passed to every query, including engagement_events, is retentionMs before now", async () => {
  queryLog = [];
  rowCounts = { items: 0, tags: 0, itemTags: 0, engagementEvents: 0 };
  const retentionMs = 5 * 24 * 60 * 60 * 1000;
  const before = Date.now() - retentionMs;

  await purgeOldTombstones(retentionMs);

  const after = Date.now() - retentionMs;
  assert.equal(queryLog.length, 4);
  for (const call of queryLog) {
    const cutoff = call.params[0] as number;
    assert.ok(cutoff >= before && cutoff <= after, `cutoff ${cutoff} should be ~${before}-${after}`);
  }
});

test("purgeOldTombstones: a zero row count is reported correctly, not treated as an error", async () => {
  queryLog = [];
  rowCounts = { items: 0, tags: 0, itemTags: 0, engagementEvents: 0 };

  const result = await purgeOldTombstones(90 * 24 * 60 * 60 * 1000);

  assert.deepEqual(result, { items: 0, tags: 0, itemTags: 0, engagementEvents: 0 });
});

// -----------------------------------------------------------------
// pullChanges pagination (S3)
// -----------------------------------------------------------------

test("pullChanges: everything fits in one page -> hasMore is false, nextCursor is the max seq", async () => {
  fakeTables = {
    items: [itemRow(1), itemRow(2)],
    tags: [tagRow(3)],
    item_tags: [],
    engagement_events: [],
  };

  const result = await pullChanges("u1", 0, 10);

  assert.equal(result.items.length, 2);
  assert.equal(result.tags.length, 1);
  assert.equal(result.hasMore, false);
  assert.equal(result.nextCursor, 3);
});

test("pullChanges: no changes -> empty result, hasMore false, nextCursor echoes since", async () => {
  fakeTables = { items: [], tags: [], item_tags: [], engagement_events: [] };

  const result = await pullChanges("u1", 42, 10);

  assert.equal(result.items.length, 0);
  assert.equal(result.hasMore, false);
  assert.equal(result.nextCursor, 42);
});

test("pullChanges: more rows than pageLimit -> hasMore true, page boundary respects global seq order across tables (no skipped rows)", async () => {
  // 5 total changes across 2 tables, interleaved by seq: item(1), tag(2), item(3), tag(4), item(5).
  fakeTables = {
    items: [itemRow(1), itemRow(3), itemRow(5)],
    tags: [tagRow(2), tagRow(4)],
    item_tags: [],
    engagement_events: [],
  };

  const page1 = await pullChanges("u1", 0, 3);
  assert.equal(page1.hasMore, true);
  assert.equal(page1.nextCursor, 3); // 3rd-lowest seq overall, not 3rd item-table row
  assert.equal(page1.items.length, 2); // seq 1 and 3
  assert.equal(page1.tags.length, 1); // seq 2

  const page2 = await pullChanges("u1", page1.nextCursor, 3);
  assert.equal(page2.hasMore, false);
  assert.equal(page2.nextCursor, 5);
  assert.equal(page2.items.length, 1); // seq 5
  assert.equal(page2.tags.length, 1); // seq 4

  // Concatenating both pages must reproduce exactly the full unpaginated result, in order.
  const allItemSeqs = [...page1.items, ...page2.items].map((i) => i.seq);
  const allTagSeqs = [...page1.tags, ...page2.tags].map((t) => t.seq);
  assert.deepEqual(allItemSeqs, [1, 3, 5]);
  assert.deepEqual(allTagSeqs, [2, 4]);
});

test("pullChanges: a page landing exactly on the last row still reports hasMore false on the next (empty) page", async () => {
  fakeTables = { items: [itemRow(1), itemRow(2)], tags: [], item_tags: [], engagement_events: [] };

  const page1 = await pullChanges("u1", 0, 2); // exactly matches available rows
  assert.equal(page1.hasMore, true); // boundary query returned exactly pageLimit rows -- ambiguous, must check again
  assert.equal(page1.items.length, 2);

  const page2 = await pullChanges("u1", page1.nextCursor, 2);
  assert.equal(page2.hasMore, false);
  assert.equal(page2.items.length, 0);
  assert.equal(page2.nextCursor, page1.nextCursor); // no new data, cursor doesn't move
});

test("pullChanges: rows belonging to a different user are never returned", async () => {
  fakeTables = {
    items: [itemRow(1, { user_id: "other-user" }), itemRow(2, { user_id: "u1" })],
    tags: [],
    item_tags: [],
    engagement_events: [],
  };

  const result = await pullChanges("u1", 0, 10);

  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].id, "item-2");
});
