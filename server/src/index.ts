import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import { accountRouter } from "./account/routes.js";
import { config } from "./config.js";
import { extractArticle } from "./extract/article.js";
import { extractYouTube } from "./extract/youtube.js";
import { enrichWithGemini } from "./gemini.js";
import { getOrCompute, hashContent } from "./cache.js";
import { getCallLimiterSnapshot, tryConsumeGeminiCall } from "./callLimiter.js";
import { getMetricsSnapshot, logAndRecord } from "./metrics.js";
import { syncRouter } from "./sync/routes.js";
import {
  EnrichmentError,
  QuotaExceededError,
  type EnrichRequest,
  type EnrichResponse,
  type ExtractedContent,
  type ItemType,
} from "./types.js";

const app = express();
app.use(helmet());
app.use(express.json());

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
  const body = req.body as Partial<EnrichRequest>;

  if (!body.id || !body.type) {
    res.status(400).json({ error: "Missing required fields: id, type" });
    return;
  }

  const { id, type, sourceUrl, bodyText } = body as EnrichRequest;

  try {
    const extracted = await extractContent(type, sourceUrl, bodyText);
    const hash = hashContent(extracted.content);

    const { result: llmResult, cacheHit } = await getOrCompute(hash, async () => {
      if (!tryConsumeGeminiCall()) {
        throw new QuotaExceededError("Daily enrichment call limit reached — try again tomorrow");
      }
      return enrichWithGemini(type, extracted.content);
    });

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
      res.status(429).json({ error: err.message });
      return;
    }
    const message = err instanceof EnrichmentError ? err.message : "Enrichment failed";
    res.status(422).json({ error: message });
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

app.listen(config.port, () => {
  console.log(`Aniki enrichment server listening on :${config.port}`);
});
