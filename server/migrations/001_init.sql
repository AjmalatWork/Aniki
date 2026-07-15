-- One shared sequence across items/tags/item_tags/engagement_events. GET /sync uses a single
-- cursor value compared against all four tables' `seq` columns; that's only correct if `seq` is
-- one global monotonic counter. Independent per-table BIGSERIALs (as a literal reading of the
-- brief's schema would produce) would let a cursor advance past one table's seq value and
-- permanently skip a later row in another table that happens to reuse that same numeric seq.
CREATE SEQUENCE IF NOT EXISTS sync_seq;

CREATE TABLE IF NOT EXISTS users (
  id          TEXT PRIMARY KEY,           -- Firebase uid
  email       TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS items (
  id              UUID PRIMARY KEY,        -- same UUID as the client
  user_id         TEXT NOT NULL REFERENCES users(id),
  type            TEXT NOT NULL,
  source_url      TEXT,
  normalized_url  TEXT,
  title           TEXT NOT NULL,
  body_text       TEXT,
  summary         TEXT,
  thumbnail_url   TEXT,
  category        TEXT,
  entities        JSONB,
  event_date      DATE,
  status          TEXT NOT NULL,
  is_starred      BOOLEAN NOT NULL DEFAULT false,
  summary_locked  BOOLEAN NOT NULL DEFAULT false,
  tags_locked     BOOLEAN NOT NULL DEFAULT false,
  updated_at      BIGINT NOT NULL,         -- content/LWW clock (client device-millis)
  deleted_at      BIGINT,                  -- tombstone (null = live)
  seq             BIGINT NOT NULL DEFAULT nextval('sync_seq'), -- replication cursor, server-assigned
  synced_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS items_user_seq_idx ON items (user_id, seq);

CREATE TABLE IF NOT EXISTS tags (
  id UUID PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id),
  label TEXT NOT NULL, origin TEXT NOT NULL,
  updated_at BIGINT NOT NULL, deleted_at BIGINT,
  seq BIGINT NOT NULL DEFAULT nextval('sync_seq'), synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, label)
);
CREATE INDEX IF NOT EXISTS tags_user_seq_idx ON tags (user_id, seq);

CREATE TABLE IF NOT EXISTS item_tags (
  item_id UUID, tag_id UUID, user_id TEXT NOT NULL,
  deleted_at BIGINT, updated_at BIGINT NOT NULL,
  seq BIGINT NOT NULL DEFAULT nextval('sync_seq'), synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (item_id, tag_id)
);
CREATE INDEX IF NOT EXISTS item_tags_user_seq_idx ON item_tags (user_id, seq);

CREATE TABLE IF NOT EXISTS engagement_events (
  id UUID PRIMARY KEY, user_id TEXT NOT NULL,
  item_id UUID NOT NULL, event_type TEXT NOT NULL, value DOUBLE PRECISION,
  created_at BIGINT NOT NULL, seq BIGINT NOT NULL DEFAULT nextval('sync_seq'),
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS engagement_events_user_seq_idx ON engagement_events (user_id, seq);
