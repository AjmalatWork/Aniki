import type { ItemDto, ItemTagDto, PulledItemTagDto, PulledTagDto, TagDto } from "./types.js";

export type UpsertDecision = "KEEP_STORED" | "NO_OP" | "OVERWRITE";

/**
 * Pure LWW ("newer/equal updatedAt wins") + content-equality-skip decision, shared by
 * upsertItem/upsertExistingTagRow/upsertItemTag in repo.ts. Extracted so this — the single most
 * important piece of logic in the sync engine — is testable without a live Postgres connection,
 * mirroring the client-side equivalent (see the Android app's sync/MergeLogic.kt decideMerge).
 */
export function decideUpsert(incomingUpdatedAt: number, storedUpdatedAt: number, contentEqual: boolean): UpsertDecision {
  if (incomingUpdatedAt < storedUpdatedAt) return "KEEP_STORED";
  if (contentEqual) return "NO_OP";
  return "OVERWRITE";
}

export function itemContentEqual(a: ItemDto, b: ItemDto): boolean {
  return (
    a.updatedAt === b.updatedAt &&
    a.type === b.type &&
    a.sourceUrl === b.sourceUrl &&
    a.normalizedUrl === b.normalizedUrl &&
    a.title === b.title &&
    a.bodyText === b.bodyText &&
    a.summary === b.summary &&
    a.thumbnailUrl === b.thumbnailUrl &&
    a.category === b.category &&
    JSON.stringify(a.entities) === JSON.stringify(b.entities) &&
    a.eventDate === b.eventDate &&
    a.status === b.status &&
    a.isStarred === b.isStarred &&
    a.summaryLocked === b.summaryLocked &&
    a.tagsLocked === b.tagsLocked &&
    a.titleLocked === b.titleLocked &&
    a.deletedAt === b.deletedAt
  );
}

export function tagContentEqual(incoming: TagDto, stored: PulledTagDto): boolean {
  return (
    incoming.updatedAt === stored.updatedAt &&
    incoming.label === stored.label &&
    incoming.origin === stored.origin &&
    incoming.deletedAt === stored.deletedAt
  );
}

export function itemTagContentEqual(incoming: ItemTagDto, stored: PulledItemTagDto): boolean {
  return incoming.updatedAt === stored.updatedAt && incoming.deletedAt === stored.deletedAt;
}
