import { pool } from "../db/pool.js";

/** Full JSON dump of a user's active library: items, tags, item_tags, engagement events. Deleted (tombstoned) rows are excluded — they're not part of the user's readable library anymore. */
export async function exportUserData(uid: string): Promise<Record<string, unknown>> {
  const [users, items, tags, itemTags, engagementEvents] = await Promise.all([
    pool.query("SELECT id, email, created_at FROM users WHERE id = $1", [uid]),
    pool.query("SELECT * FROM items WHERE user_id = $1 AND deleted_at IS NULL ORDER BY seq", [uid]),
    pool.query("SELECT * FROM tags WHERE user_id = $1 AND deleted_at IS NULL ORDER BY seq", [uid]),
    pool.query("SELECT * FROM item_tags WHERE user_id = $1 AND deleted_at IS NULL ORDER BY seq", [uid]),
    pool.query("SELECT * FROM engagement_events WHERE user_id = $1 ORDER BY created_at", [uid]),
  ]);

  return {
    exportedAt: new Date().toISOString(),
    account: users.rows[0] ?? null,
    items: items.rows,
    tags: tags.rows,
    itemTags: itemTags.rows,
    engagementEvents: engagementEvents.rows,
  };
}

/** Irreversibly deletes every row belonging to this user, children before parents (no ON DELETE CASCADE in the schema), then the user row itself. */
export async function deleteUserAccount(uid: string): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query("BEGIN");
    await client.query("DELETE FROM engagement_events WHERE user_id = $1", [uid]);
    await client.query("DELETE FROM item_tags WHERE user_id = $1", [uid]);
    await client.query("DELETE FROM items WHERE user_id = $1", [uid]);
    await client.query("DELETE FROM tags WHERE user_id = $1", [uid]);
    await client.query("DELETE FROM users WHERE id = $1", [uid]);
    await client.query("COMMIT");
  } catch (err) {
    await client.query("ROLLBACK");
    throw err;
  } finally {
    client.release();
  }
}
