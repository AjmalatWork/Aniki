import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import { accountRouter } from "./account/routes.js";
import { config } from "./config.js";
import { extractArticle } from "./extract/article.js";
import { extractYouTube } from "./extract/youtube.js";
import { enrichWithGemini } from "./gemini.js";
import { getOrCompute, hashContent } from "./cache.js";
import { getCallLimiterSnapshot, tryConsumeGeminiCall } from "./callLimiter.js";
import { isRateLimited } from "./ipRateLimiter.js";
import { getMetricsSnapshot, logAndRecord } from "./metrics.js";
import { syncRouter } from "./sync/routes.js";
import { purgeOldTombstones } from "./sync/repo.js";
import {
  EnrichmentError,
  QuotaExceededError,
  type EnrichRequest,
  type EnrichResponse,
  type ExtractedContent,
  type ExtractThumbnailRequest,
  type ExtractThumbnailResponse,
  type ItemType,
} from "./types.js";

const app = express();
// Render (and any single-reverse-proxy host) terminates the real client connection and forwards
// over its internal network -- without this, req.ip resolves to the proxy's own address for every
// request, which silently turns the per-IP /enrich rate limiter below into one shared bucket for
// the entire userbase instead of an actual per-abuser guard. `1` = trust exactly one hop
// (Render's edge), so X-Forwarded-For is honored but a client can't spoof further hops behind it.
app.set("trust proxy", 1);
app.use(helmet());
// Default body-parser limit is ~100kb -- too small for a long NOTE bodyText or a large sync push
// batch. 2mb comfortably covers both without opening the door to unbounded request bodies.
app.use(express.json({ limit: "2mb" }));

app.get("/health", (_req: Request, res: Response) => {
  res.json({ ok: true });
});

// Local-only observability view (Slice 6) — in-memory counters, not a metrics service.
app.get("/metrics", (_req: Request, res: Response) => {
  res.json({ requests: getMetricsSnapshot(), geminiCalls: getCallLimiterSnapshot() });
});

// /enrich stays unauthenticated (Slice 3 decision): it's stateless and holds no user data,
// so there's nothing there for a token check to protect. /sync is the one that's scoped to uid.
app.use("/sync", syncRouter);
app.use("/account", accountRouter);

app.post("/enrich", async (req: Request, res: Response) => {
  const start = Date.now();

  // Per-IP guard: /enrich is unauthenticated, so without this one caller can exhaust the global
  // daily Gemini cap before any real user gets a turn. req.ip is the direct socket address --
  // if this ever runs behind a reverse proxy, `app.set("trust proxy", ...)` needs to be
  // configured for X-Forwarded-For to be honored instead.
  if (isRateLimited(req.ip ?? "unknown")) {
    logAndRecord(`[POST /enrich] rate limited ip=${req.ip}`, "/enrich", Date.now() - start, true);
    res.status(429).json({ error: "Too many requests — try again in a moment", code: "RATE_LIMITED" });
    return;
  }

  const body = req.body as Partial<EnrichRequest>;

  if (!body.id || !body.type) {
    res.status(400).json({ error: "Missing required fields: id, type" });
    return;
  }

  const { id, type, sourceUrl, bodyText } = body as EnrichRequest;

  try {
    const extracted = await extractContent(type, sourceUrl, bodyText);
    const hash = hashContent(extracted.content);

    const { result: llmResult, cacheHit } = await getOrCompute(hash, async () =>
      enrichWithGemini(type, extracted.content, tryConsumeGeminiCall)
    );

    const response: EnrichResponse = {
      id,
      title: llmResult.title ?? extracted.title,
      summary: llmResult.summary,
      category: llmResult.category,
      tags: llmResult.tags,
      entities: llmResult.entities,
      thumbnailUrl: extracted.thumbnailUrl,
      eventDate: llmResult.eventDate,
    };

    logAndRecord(requestLogLine(req, type, cacheHit, Date.now() - start), "/enrich", Date.now() - start, false);
    res.status(200).json(response);
  } catch (err) {
    logAndRecord(
      requestLogLine(req, type, false, Date.now() - start, err),
      "/enrich",
      Date.now() - start,
      true
    );
    if (err instanceof QuotaExceededError) {
      res.status(429).json({ error: err.message, code: "QUOTA_EXCEEDED" });
      return;
    }
    const message = err instanceof EnrichmentError ? err.message : "Enrichment failed";
    const code = err instanceof EnrichmentError ? err.code : "GENERIC";
    res.status(422).json({ error: message, code });
  }
});

/**
 * Extraction-only re-check for an article's OG/twitter:image, used purely for the client's lazy
 * thumbnail backfill (approved design: articles saved before OG-image extraction existed get
 * their thumbnail filled in lazily). Deliberately never touches Gemini -- extraction is the only
 * source of thumbnailUrl (see /enrich's EnrichResponse construction), so this has zero cost
 * against the daily call cap, unlike a full re-enrichment would.
 */
app.post("/extract-thumbnail", async (req: Request, res: Response) => {
  const start = Date.now();

  // Shares /enrich's per-IP bucket: same unauthenticated, network-triggering shape, same abuse risk.
  if (isRateLimited(req.ip ?? "unknown")) {
    logAndRecord(`[POST /extract-thumbnail] rate limited ip=${req.ip}`, "/extract-thumbnail", Date.now() - start, true);
    res.status(429).json({ error: "Too many requests — try again in a moment" });
    return;
  }

  const body = req.body as Partial<ExtractThumbnailRequest>;
  if (!body.sourceUrl) {
    res.status(400).json({ error: "Missing required field: sourceUrl" });
    return;
  }

  // extractArticle() can throw once it's past thumbnail resolution -- e.g. the page has no
  // readable content AND no OG title/description for the (irrelevant here) content fallback.
  // That's still a "no thumbnail found" outcome for this endpoint, not a real error, so any
  // failure degrades to thumbnailUrl: null / 200 rather than a 422 the client would have to
  // special-case. Only a malformed request (missing sourceUrl) is a genuine 4xx, handled above.
  try {
    const extracted = await extractArticle(body.sourceUrl);
    const response: ExtractThumbnailResponse = { thumbnailUrl: extracted.thumbnailUrl };
    logAndRecord(
      `[POST /extract-thumbnail] thumbnailUrl=${extracted.thumbnailUrl !== null} latency=${Date.now() - start}ms`,
      "/extract-thumbnail",
      Date.now() - start,
      false
    );
    res.status(200).json(response);
  } catch (err) {
    logAndRecord(
      `[POST /extract-thumbnail] no thumbnail: ${(err as Error).message} latency=${Date.now() - start}ms`,
      "/extract-thumbnail",
      Date.now() - start,
      false
    );
    res.status(200).json({ thumbnailUrl: null } satisfies ExtractThumbnailResponse);
  }
});

async function extractContent(
  type: ItemType,
  sourceUrl: string | null,
  bodyText: string | null
): Promise<ExtractedContent> {
  switch (type) {
    case "WEB_ARTICLE":
      if (!sourceUrl) throw new EnrichmentError("sourceUrl is required for WEB_ARTICLE");
      return extractArticle(sourceUrl);
    case "YOUTUBE_VIDEO":
      if (!sourceUrl) throw new EnrichmentError("sourceUrl is required for YOUTUBE_VIDEO");
      return extractYouTube(sourceUrl);
    case "NOTE":
      if (!bodyText) throw new EnrichmentError("bodyText is required for NOTE");
      return { content: bodyText, title: null, thumbnailUrl: null };
    default:
      throw new EnrichmentError(`Unknown type: ${type as string}`);
  }
}

function requestLogLine(
  req: Request,
  type: string,
  cacheHit: boolean,
  latencyMs: number,
  err?: unknown
): string {
  const status = err ? "error" : cacheHit ? "cache hit" : "cache miss";
  const errSuffix = err ? ` error="${(err as Error).message}"` : "";
  return `[${req.method} ${req.path}] type=${type} ${status} latency=${latencyMs}ms${errSuffix}`;
}

// Terminal error handler: anything thrown outside a route's own try/catch (most notably
// express.json() rejecting a malformed request body) previously fell through to Express's
// default HTML error page, which the Android client's JSON-only Response<T> handling can't
// parse. This keeps every error response the same shape the client already expects.
app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  const status =
    (err as { status?: number; statusCode?: number } | null)?.status ??
    (err as { status?: number; statusCode?: number } | null)?.statusCode ??
    500;
  const message = err instanceof Error ? err.message : "Unexpected server error";
  console.log(`[unhandled] status=${status} error="${message}"`);
  res.status(status).json({ error: message });
});

const ONE_DAY_MS = 24 * 60 * 60 * 1000;

async function runTombstoneGc(): Promise<void> {
  try {
    const result = await purgeOldTombstones(config.tombstoneRetentionDays * ONE_DAY_MS);
    console.log(
      `[tombstone-gc] purged items=${result.items} tags=${result.tags} itemTags=${result.itemTags} ` +
        `retentionDays=${config.tombstoneRetentionDays}`
    );
  } catch (err) {
    // Never fatal -- e.g. Postgres not reachable in local dev without Docker running. /enrich
    // and everything else keeps working; the sweep just retries on the next interval.
    console.log(`[tombstone-gc] failed: ${(err as Error).message}`);
  }
}

app.listen(config.port, () => {
  console.log(`Aniki enrichment server listening on :${config.port}`);
  void runTombstoneGc();
  setInterval(() => void runTombstoneGc(), ONE_DAY_MS);
});
