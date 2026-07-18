/**
 * Pure predicate extracted from POST /enrich (SEC3 of the maintainability audit) so the cap check
 * is directly testable without spinning up a real Express server. Only NOTE is checked -- a link's
 * bodyText (when present at all) is raw share-sheet text handed off for extraction, not the
 * user-authored content itself, and is bounded by extraction rather than this cap.
 */
export function isNoteBodyTooLong(type: string, bodyText: string | null, maxChars: number): boolean {
  return type === "NOTE" && bodyText !== null && bodyText.length > maxChars;
}
