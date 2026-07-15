import express, { type Request, type Response } from "express";
import { accountRouter } from "./account/routes.js";
import { config } from "./config.js";
import { extractArticle } from "./extract/article.js";
import { extractYouTube } from "./extract/youtube.js";
import { enrichWithGemini } from "./gemini.js";
import { getCached, hashContent, setCached } from "./cache.js";
import { getCallLimiterSnapshot, tryConsumeGeminiCall } from "./callLimiter.js";
import { getMetricsSnapshot, recordRequest } from "./metrics.js";
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

    const cached = getCached(hash);
    const cacheHit = cached !== undefined;
    if (!cacheHit && !tryConsumeGeminiCall()) {
      throw new QuotaExceededError("Daily enrichment call limit reached — try again tomorrow");
    }
    const llmResult = cached ?? (await enrichWithGemini(type, extracted.content));
    if (!cacheHit) setCached(hash, llmResult);

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

    logRequest(req, type, cacheHit, Date.now() - start);
    recordRequest("/enrich", Date.now() - start, false);
    res.status(200).json(response);
  } catch (err) {
    logRequest(req, type, false, Date.now() - start, err);
    recordRequest("/enrich", Date.now() - start, true);
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

function logRequest(
  req: Request,
  type: string,
  cacheHit: boolean,
  latencyMs: number,
  err?: unknown
): void {
  const status = err ? "error" : cacheHit ? "cache hit" : "cache miss";
  const errSuffix = err ? ` error="${(err as Error).message}"` : "";
  console.log(
    `[${req.method} ${req.path}] type=${type} ${status} latency=${latencyMs}ms${errSuffix}`
  );
}

app.listen(config.port, () => {
  console.log(`Aniki enrichment server listening on :${config.port}`);
});
