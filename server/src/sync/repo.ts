import type { PoolClient } from "pg";
import { pool } from "../db/pool.js";
import { decideUpsert, itemContentEqual, itemTagContentEqual, tagContentEqual } from "./mergeLogic.js";
import type {
  Ack,
  EngagementEventDto,
  ItemDto,
  ItemTagAck,
  ItemTagDto,
  PulledEngagementEventDto,
  PulledItemDto,
  PulledItemTagDto,
  PulledTagDto,
  PullResponse,
  PushRequest,
  PushResponse,
  SyncEntities,
  TagDto,
} from "./types.js";

export async function ensureUser(uid: string, email: string | null): Promise<void> {
  await pool.query(
    `INSERT INTO users (id, email) VALUES ($1, $2)
     ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email
     WHERE users.email IS DISTINCT FROM EXCLUDED.email`,
    [uid, email]
  );
}

// ---------------------------------------------------------------------------
// Pull
// ---------------------------------------------------------------------------

function formatDate(d: unknown): string | null {
  if (!d) return null;
  const date = d instanceof Date ? d : new Date(d as string);
  return date.toISOString().slice(0, 10);
}

interface ItemRow {
  id: string;
  type: string;
  source_url: string | null;
  normalized_url: string | null;
  title: string;
  body_text: string | null;
  summary: string | null;
  thumbnail_url: string | null;
  category: string | null;
  entities: SyncEntities | null;
  event_date: unknown;
  status: string;
  is_starred: boolean;
  summary_locked: boolean;
  tags_locked: boolean;
  title_locked: boolean;
  updated_at: number;
  deleted_at: number | null;
  seq: number;
}

function itemRowToDto(row: ItemRow): PulledItemDto {
  return {
    id: row.id,
    type: row.type,
    sourceUrl: row.source_url,
    normalizedUrl: row.normalized_url,
    title: row.title,
    bodyText: row.body_text,
    summary: row.summary,
    thumbnailUrl: row.thumbnail_url,
    category: row.category,
    entities: row.entities,
    eventDate: formatDate(row.event_date),
    status: row.status,
    isStarred: row.is_starred,
    summaryLocked: row.summary_locked,
    tagsLocked: row.tags_locked,
    titleLocked: row.title_locked,
    updatedAt: row.updated_at,
    deletedAt: row.deleted_at,
    seq: row.seq,
  };
}

interface TagRow {
  id: string;
  label: string;
  origin: string;
  updated_at: number;
  deleted_at: number | null;
  seq: number;
}

function tagRowToDto(row: TagRow): PulledTagDto {
  return {
    id: row.id,
    label: row.label,
    origin: row.origin,
    updatedAt: row.updated_at,
    deletedAt: row.deleted_at,
    seq: row.seq,
  };
}

interface ItemTagRow {
  item_id: string;
  tag_id: string;
  updated_at: number;
  deleted_at: number | null;
  seq: number;
}

function itemTagRowToDto(row: ItemTagRow): PulledItemTagDto {
  return {
    itemId: row.item_id,
    tagId: row.tag_id,
    updatedAt: row.updated_at,
    deletedAt: row.deleted_at,
    seq: row.seq,
  };
}

interface EventRow {
  id: string;
  item_id: string;
  event_type: string;
  value: number | null;
  created_at: number;
  seq: number;
}

function eventRowToDto(row: EventRow): PulledEngagementEventDto {
  return {
    id: row.id,
    itemId: row.item_id,
    eventType: row.event_type,
    value: row.value,
    createdAt: row.created_at,
    seq: row.seq,
  };
}

/**
 * Paginated pull: `seq` is a single shared sequence across all 4 synced tables (see the schema
 * comment in migrations/001_init.sql), so "the next N changes across all tables, in seq order"
 * is a well-defined page boundary. We find that boundary with one cheap UNION-ALL query over just
 * the seq column, then fetch each table's full rows bounded to (since, boundary] -- this is what
 * guarantees no row is skipped: every synced row has a globally unique, monotonically increasing
 * seq, so nothing between two page boundaries can fall through the gap between per-table queries.
 */
export async function pullChanges(uid: string, since: number, pageLimit: number): Promise<PullResponse> {
  const boundaryRes = await pool.query<{ seq: number }>(
    `SELECT seq FROM (
       SELECT seq FROM items WHERE user_id = $1 AND seq > $2
       UNION ALL
       SELECT seq FROM tags WHERE user_id = $1 AND seq > $2
       UNION ALL
       SELECT seq FROM item_tags WHERE user_id = $1 AND seq > $2
       UNION ALL
       SELECT seq FROM engagement_events WHERE user_id = $1 AND seq > $2
     ) all_changed_seqs
     ORDER BY seq ASC
     LIMIT $3`,
    [uid, since, pageLimit]
  );

  const hasMore = boundaryRes.rowCount === pageLimit;
  const boundary = boundaryRes.rowCount! > 0 ? boundaryRes.rows[boundaryRes.rowCount! - 1].seq : since;

  const [itemsRes, tagsRes, itemTagsRes, eventsRes] = await Promise.all([
    pool.query<ItemRow>(
      "SELECT * FROM items WHERE user_id = $1 AND seq > $2 AND seq <= $3 ORDER BY seq ASC",
      [uid, since, boundary]
    ),
    pool.query<TagRow>(
      "SELECT * FROM tags WHERE user_id = $1 AND seq > $2 AND seq <= $3 ORDER BY seq ASC",
      [uid, since, boundary]
    ),
    pool.query<ItemTagRow>(
      "SELECT * FROM item_tags WHERE user_id = $1 AND seq > $2 AND seq <= $3 ORDER BY seq ASC",
      [uid, since, boundary]
    ),
    pool.query<EventRow>(
      "SELECT * FROM engagement_events WHERE user_id = $1 AND seq > $2 AND seq <= $3 ORDER BY seq ASC",
      [uid, since, boundary]
    ),
  ]);

  return {
    items: itemsRes.rows.map(itemRowToDto),
    tags: tagsRes.rows.map(tagRowToDto),
    itemTags: itemTagsRes.rows.map(itemTagRowToDto),
    engagementEvents: eventsRes.rows.map(eventRowToDto),
    nextCursor: boundary,
    hasMore,
  };
}

// ---------------------------------------------------------------------------
// Push — each function: LWW ("newer/equal updatedAt wins") + a content-equality
// skip so a byte-identical retry (e.g. after an ambiguous network failure) never
// bumps seq or touches synced_at. Runs inside the caller's transaction.
// ---------------------------------------------------------------------------

async function upsertItem(client: PoolClient, uid: string, incoming: ItemDto): Promise<Ack> {
  const existing = await client.query<ItemRow>("SELECT * FROM items WHERE id = $1 AND user_id = $2 FOR UPDATE", [
    incoming.id,
    uid,
  ]);

  if (existing.rowCount === 0) {
    const inserted = await client.query<{ seq: number }>(
      `INSERT INTO items (id, user_id, type, source_url, normalized_url, title, body_text, summary,
                           thumbnail_url, category, entities, event_date, status, is_starred,
                           summary_locked, tags_locked, title_locked, updated_at, deleted_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
       RETURNING seq`,
      [
        incoming.id,
        uid,
        incoming.type,
        incoming.sourceUrl,
        incoming.normalizedUrl,
        incoming.title,
        incoming.bodyText,
        incoming.summary,
        incoming.thumbnailUrl,
        incoming.category,
        incoming.entities ? JSON.stringify(incoming.entities) : null,
        incoming.eventDate,
        incoming.status,
        incoming.isStarred,
        incoming.summaryLocked,
        incoming.tagsLocked,
        incoming.titleLocked,
        incoming.updatedAt,
        incoming.deletedAt,
      ]
    );
    return { clientId: incoming.id, id: incoming.id, seq: inserted.rows[0].seq };
  }

  const stored = itemRowToDto(existing.rows[0]);

  const decision = decideUpsert(incoming.updatedAt, stored.updatedAt, itemContentEqual(incoming, stored));
  if (decision === "KEEP_STORED" || decision === "NO_OP") {
    // KEEP_STORED: server is strictly newer, keep stored, ack its current seq unchanged.
    // NO_OP: true no-op retry (e.g. client never saw our ack) — don't bump seq/synced_at.
    return { clientId: incoming.id, id: incoming.id, seq: stored.seq };
  }

  const updated = await client.query<{ seq: number }>(
    `UPDATE items SET
       type = $3, source_url = $4, normalized_url = $5, title = $6, body_text = $7, summary = $8,
       thumbnail_url = $9, category = $10, entities = $11, event_date = $12, status = $13,
       is_starred = $14, summary_locked = $15, tags_locked = $16, title_locked = $17,
       updated_at = $18, deleted_at = $19,
       seq = nextval('sync_seq'), synced_at = now()
     WHERE id = $1 AND user_id = $2
     RETURNING seq`,
    [
      incoming.id,
      uid,
      incoming.type,
      incoming.sourceUrl,
      incoming.normalizedUrl,
      incoming.title,
      incoming.bodyText,
      incoming.summary,
      incoming.thumbnailUrl,
      incoming.category,
      incoming.entities ? JSON.stringify(incoming.entities) : null,
      incoming.eventDate,
      incoming.status,
      incoming.isStarred,
      incoming.summaryLocked,
      incoming.tagsLocked,
      incoming.titleLocked,
      incoming.updatedAt,
      incoming.deletedAt,
    ]
  );
  return { clientId: incoming.id, id: incoming.id, seq: updated.rows[0].seq };
}

/**
 * Tags are additionally unique on (user_id, label). If two devices independently create a tag
 * with the same label before ever syncing, they'll arrive with different client-generated ids.
 * We resolve that by treating (user_id, label) as the true identity: on a label collision with a
 * different id, we upsert the *existing* row (LWW on updatedAt) and ack with its id instead of
 * the pushed id, so the client can re-key its local tag row and any item_tags pointing at it.
 */
async function upsertTag(client: PoolClient, uid: string, incoming: TagDto): Promise<Ack> {
  const byId = await client.query<TagRow>("SELECT * FROM tags WHERE id = $1 AND user_id = $2 FOR UPDATE", [
    incoming.id,
    uid,
  ]);

  if (byId.rowCount === 0) {
    const byLabel = await client.query<TagRow>(
      "SELECT * FROM tags WHERE user_id = $1 AND label = $2 FOR UPDATE",
      [uid, incoming.label]
    );

    if (byLabel.rowCount === 0) {
      const inserted = await client.query<{ seq: number }>(
        `INSERT INTO tags (id, user_id, label, origin, updated_at, deleted_at)
         VALUES ($1,$2,$3,$4,$5,$6) RETURNING seq`,
        [incoming.id, uid, incoming.label, incoming.origin, incoming.updatedAt, incoming.deletedAt]
      );
      return { clientId: incoming.id, id: incoming.id, seq: inserted.rows[0].seq };
    }

    // Label collision under a different id: reconcile onto the existing canonical row.
    return upsertExistingTagRow(client, uid, incoming, tagRowToDto(byLabel.rows[0]));
  }

  return upsertExistingTagRow(client, uid, incoming, tagRowToDto(byId.rows[0]));
}

async function upsertExistingTagRow(
  client: PoolClient,
  uid: string,
  incoming: TagDto,
  stored: PulledTagDto
): Promise<Ack> {
  const decision = decideUpsert(incoming.updatedAt, stored.updatedAt, tagContentEqual(incoming, stored));
  if (decision === "KEEP_STORED" || decision === "NO_OP") {
    return { clientId: incoming.id, id: stored.id, seq: stored.seq };
  }

  const updated = await client.query<{ seq: number }>(
    `UPDATE tags SET origin = $2, updated_at = $3, deleted_at = $4, seq = nextval('sync_seq'), synced_at = now()
     WHERE id = $1 AND user_id = $5
     RETURNING seq`,
    [stored.id, incoming.origin, incoming.updatedAt, incoming.deletedAt, uid]
  );
  return { clientId: incoming.id, id: stored.id, seq: updated.rows[0].seq };
}

async function upsertItemTag(client: PoolClient, uid: string, incoming: ItemTagDto): Promise<ItemTagAck> {
  const existing = await client.query<ItemTagRow>(
    "SELECT * FROM item_tags WHERE item_id = $1 AND tag_id = $2 AND user_id = $3 FOR UPDATE",
    [incoming.itemId, incoming.tagId, uid]
  );

  if (existing.rowCount === 0) {
    const inserted = await client.query<{ seq: number }>(
      `INSERT INTO item_tags (item_id, tag_id, user_id, deleted_at, updated_at)
       VALUES ($1,$2,$3,$4,$5) RETURNING seq`,
      [incoming.itemId, incoming.tagId, uid, incoming.deletedAt, incoming.updatedAt]
    );
    return { itemId: incoming.itemId, tagId: incoming.tagId, seq: inserted.rows[0].seq };
  }

  const stored = itemTagRowToDto(existing.rows[0]);
  const decision = decideUpsert(incoming.updatedAt, stored.updatedAt, itemTagContentEqual(incoming, stored));
  if (decision === "KEEP_STORED" || decision === "NO_OP") {
    return { itemId: incoming.itemId, tagId: incoming.tagId, seq: stored.seq };
  }

  const updated = await client.query<{ seq: number }>(
    `UPDATE item_tags SET deleted_at = $3, updated_at = $4, seq = nextval('sync_seq'), synced_at = now()
     WHERE item_id = $1 AND tag_id = $2 AND user_id = $5
     RETURNING seq`,
    [incoming.itemId, incoming.tagId, incoming.deletedAt, incoming.updatedAt, uid]
  );
  return { itemId: incoming.itemId, tagId: incoming.tagId, seq: updated.rows[0].seq };
}

/** Append-only, one-way, insert-if-not-exists — never conflicts, never updated. */
async function upsertEngagementEvent(client: PoolClient, uid: string, incoming: EngagementEventDto): Promise<Ack> {
  // Scoped by user_id like every other table's upsert (upsertItem/upsertTag/upsertItemTag) --
  // without it, a client-generated UUID colliding with another user's event id would leak that
  // user's seq in the ack and silently drop this user's own event via ON CONFLICT DO NOTHING.
  const existing = await client.query<{ seq: number }>(
    "SELECT seq FROM engagement_events WHERE id = $1 AND user_id = $2",
    [incoming.id, uid]
  );
  if (existing.rowCount !== 0) {
    return { clientId: incoming.id, id: incoming.id, seq: existing.rows[0].seq };
  }

  const inserted = await client.query<{ seq: number }>(
    `INSERT INTO engagement_events (id, user_id, item_id, event_type, value, created_at)
     VALUES ($1,$2,$3,$4,$5,$6)
     ON CONFLICT (id) DO NOTHING
     RETURNING seq`,
    [incoming.id, uid, incoming.itemId, incoming.eventType, incoming.value, incoming.createdAt]
  );
  if (inserted.rowCount === 0) {
    // Lost a race with a concurrent identical push; fetch the winner's seq.
    const row = await client.query<{ seq: number }>(
      "SELECT seq FROM engagement_events WHERE id = $1 AND user_id = $2",
      [incoming.id, uid]
    );
    if (row.rowCount === 0) {
      // `id` is only globally unique, not per-user (see migrations/001_init.sql) -- ON CONFLICT
      // fired against a row owned by a *different* user (a UUID collision, astronomically
      // unlikely but not impossible). Fail loudly rather than crashing on undefined access or
      // silently returning another user's seq.
      throw new Error(`engagement_events id collision across users: id=${incoming.id}`);
    }
    return { clientId: incoming.id, id: incoming.id, seq: row.rows[0].seq };
  }
  return { clientId: incoming.id, id: incoming.id, seq: inserted.rows[0].seq };
}

export async function pushChanges(uid: string, request: PushRequest): Promise<PushResponse> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");

    // Tags before item_tags: a tag row must exist (with its final, possibly-remapped id)
    // before any item_tags row referencing it is written.
    const tagAcks: Ack[] = [];
    for (const tag of request.tags) {
      tagAcks.push(await upsertTag(client, uid, tag));
    }
    const tagIdRemap = new Map(tagAcks.filter((a) => a.id !== a.clientId).map((a) => [a.clientId, a.id]));

    const itemAcks: Ack[] = [];
    for (const item of request.items) {
      itemAcks.push(await upsertItem(client, uid, item));
    }

    const itemTagAcks: ItemTagAck[] = [];
    for (const itemTag of request.itemTags) {
      const tagId = tagIdRemap.get(itemTag.tagId) ?? itemTag.tagId;
      itemTagAcks.push(await upsertItemTag(client, uid, { ...itemTag, tagId }));
    }

    const engagementEventAcks: Ack[] = [];
    for (const event of request.engagementEvents) {
      engagementEventAcks.push(await upsertEngagementEvent(client, uid, event));
    }

    await client.query("COMMIT");

    const allSeqs = [
      ...itemAcks.map((a) => a.seq),
      ...tagAcks.map((a) => a.seq),
      ...itemTagAcks.map((a) => a.seq),
      ...engagementEventAcks.map((a) => a.seq),
    ];
    const nextCursor = allSeqs.length > 0 ? Math.max(...allSeqs) : 0;

    return { itemAcks, tagAcks, itemTagAcks, engagementEventAcks, nextCursor };
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}

export interface TombstonePurgeResult {
  items: number;
  tags: number;
  itemTags: number;
}

/**
 * Hard-deletes rows tombstoned (deleted_at set) longer than `retentionMs` ago. Tombstones
 * otherwise accumulate forever -- pullChanges intentionally still returns them (so other devices
 * learn of the delete), but nothing ever purged the source rows.
 *
 * `retentionMs` is a global assumption, not a per-device one: any device that stays offline
 * longer than this window will miss the delete on its next sync and see the row as if it were
 * never deleted (a pull only returns rows with seq > its cursor; a purged tombstone just vanishes
 * from that stream rather than announcing itself). Not scoped to a single user -- this is a
 * maintenance sweep across the whole table, run on a schedule, not per-request.
 */
export async function purgeOldTombstones(retentionMs: number): Promise<TombstonePurgeResult> {
  const cutoff = Date.now() - retentionMs;
  const [items, tags, itemTags] = await Promise.all([
    pool.query("DELETE FROM items WHERE deleted_at IS NOT NULL AND deleted_at < $1", [cutoff]),
    pool.query("DELETE FROM tags WHERE deleted_at IS NOT NULL AND deleted_at < $1", [cutoff]),
    pool.query("DELETE FROM item_tags WHERE deleted_at IS NOT NULL AND deleted_at < $1", [cutoff]),
  ]);
  return { items: items.rowCount ?? 0, tags: tags.rowCount ?? 0, itemTags: itemTags.rowCount ?? 0 };
}
