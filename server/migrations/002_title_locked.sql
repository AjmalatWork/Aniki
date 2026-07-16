-- Polish-pass item 4: note titles are now Gemini-generated and user-editable, so they need the
-- same edit-lock pattern as summary_locked/tags_locked -- title_locked=true means re-enrichment
-- must never overwrite a user-edited title.
ALTER TABLE items ADD COLUMN IF NOT EXISTS title_locked BOOLEAN NOT NULL DEFAULT false;
