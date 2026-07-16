import { Router, type Request, type Response } from "express";
import { requireAuth } from "../auth/middleware.js";
import { logAndRecord } from "../metrics.js";
import { pullChanges, pushChanges } from "./repo.js";
import type { PushRequest } from "./types.js";

export const syncRouter = Router();

syncRouter.use(requireAuth);

syncRouter.get("/", async (req: Request, res: Response) => {
  const since = Number(req.query.since ?? 0);
  if (!Number.isFinite(since) || since < 0) {
    res.status(400).json({ error: "since must be a non-negative number" });
    return;
  }

  const start = Date.now();
  try {
    const result = await pullChanges(req.uid!, since);
    logAndRecord(
      `[GET /sync] uid=${req.uid} since=${since} items=${result.items.length} tags=${result.tags.length} ` +
        `itemTags=${result.itemTags.length} events=${result.engagementEvents.length} nextCursor=${result.nextCursor} ` +
        `latency=${Date.now() - start}ms`,
      "GET /sync",
      Date.now() - start,
      false
    );
    res.json(result);
  } catch (err) {
    logAndRecord(
      `[GET /sync] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`,
      "GET /sync",
      Date.now() - start,
      true
    );
    res.status(500).json({ error: "Sync pull failed" });
  }
});

syncRouter.post("/", async (req: Request, res: Response) => {
  const body = req.body as Partial<PushRequest>;
  const request: PushRequest = {
    items: body.items ?? [],
    tags: body.tags ?? [],
    itemTags: body.itemTags ?? [],
    engagementEvents: body.engagementEvents ?? [],
  };

  const start = Date.now();
  try {
    const result = await pushChanges(req.uid!, request);
    logAndRecord(
      `[POST /sync] uid=${req.uid} items=${request.items.length} tags=${request.tags.length} ` +
        `itemTags=${request.itemTags.length} events=${request.engagementEvents.length} ` +
        `nextCursor=${result.nextCursor} latency=${Date.now() - start}ms`,
      "POST /sync",
      Date.now() - start,
      false
    );
    res.json(result);
  } catch (err) {
    logAndRecord(
      `[POST /sync] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`,
      "POST /sync",
      Date.now() - start,
      true
    );
    res.status(500).json({ error: "Sync push failed" });
  }
});
