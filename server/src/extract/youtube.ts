import { fetchTranscript } from "youtube-transcript";
import { EnrichmentError, FetchFailedError, type ExtractedContent } from "../types.js";

const FETCH_TIMEOUT_MS = 15_000;

interface OEmbedResponse {
  title: string;
  author_name: string;
  thumbnail_url: string;
}

async function fetchOEmbed(url: string): Promise<OEmbedResponse> {
  const oEmbedUrl = `https://www.youtube.com/oembed?url=${encodeURIComponent(url)}&format=json`;
  try {
    const res = await fetch(oEmbedUrl, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
    if (!res.ok) {
      throw new FetchFailedError(`YouTube oEmbed failed with status ${res.status}`);
    }
    return (await res.json()) as OEmbedResponse;
  } catch (err) {
    if (err instanceof EnrichmentError) throw err;
    throw new FetchFailedError(`Could not reach YouTube oEmbed: ${(err as Error).message}`);
  }
}

async function fetchTranscriptText(url: string): Promise<string | null> {
  try {
    const segments = await fetchTranscript(url);
    const text = segments.map((s) => s.text).join(" ").trim();
    return text.length > 0 ? text : null;
  } catch {
    // No captions, disabled transcript, or the unofficial API changed shape — never fatal.
    return null;
  }
}

export async function extractYouTube(url: string): Promise<ExtractedContent> {
  const oEmbed = await fetchOEmbed(url);
  const transcript = await fetchTranscriptText(url);

  const content = transcript
    ? `Title: ${oEmbed.title}\nChannel: ${oEmbed.author_name}\n\nTranscript:\n${transcript}`
    : `Title: ${oEmbed.title}\nChannel: ${oEmbed.author_name}\n\n(No transcript available; summarize from the title and channel alone.)`;

  return {
    content,
    title: oEmbed.title,
    thumbnailUrl: oEmbed.thumbnail_url ?? null,
  };
}
