import type { PushRequest } from "./types.js";

/**
 * Pure predicate extracted from the /sync POST route (SEC3 of the maintainability audit) so the
 * cap check is directly testable without spinning up a real Express server (no supertest/HTTP test
 * client in this project's dev dependencies -- see routes.ts for why this stayed a plain function
 * instead of an integration test).
 */
export function pushRowCount(request: PushRequest): number {
  return request.items.length + request.tags.length + request.itemTags.length + request.engagementEvents.length;
}

export function exceedsPushRowCap(request: PushRequest, maxRows: number): boolean {
  return pushRowCount(request) > maxRows;
}
