import { isIP } from "node:net";
import dns from "node:dns/promises";
import { EnrichmentError } from "../types.js";

const BLOCKED_HOSTNAMES = new Set(["localhost"]);

function isPrivateIPv4(ip: string): boolean {
  const parts = ip.split(".").map(Number);
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return false;
  const [a, b] = parts;
  if (a === 0) return true; // "this network"
  if (a === 10) return true; // 10.0.0.0/8
  if (a === 127) return true; // loopback
  if (a === 169 && b === 254) return true; // link-local, incl. cloud metadata (169.254.169.254)
  if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
  if (a === 192 && b === 168) return true; // 192.168.0.0/16
  return false;
}

function isPrivateIPv6(ip: string): boolean {
  const lower = ip.toLowerCase();
  if (lower === "::1" || lower === "::") return true; // loopback / unspecified
  if (lower.startsWith("fc") || lower.startsWith("fd")) return true; // fc00::/7 unique local
  if (lower.startsWith("fe80")) return true; // fe80::/10 link-local
  const mapped = lower.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
  if (mapped) return isPrivateIPv4(mapped[1]);
  return false;
}

function isPrivateIp(ip: string): boolean {
  const version = isIP(ip);
  if (version === 4) return isPrivateIPv4(ip);
  if (version === 6) return isPrivateIPv6(ip);
  return false;
}

/**
 * SSRF guard for /enrich's WEB_ARTICLE path: the server fetches whatever `sourceUrl` the
 * (unauthenticated) client supplies, so without this an attacker can make the server probe
 * internal services, hit a cloud metadata endpoint, or use it as a request proxy. Rejects
 * non-http(s) schemes and any hostname that resolves to a private/loopback/link-local address.
 *
 * Resolves the hostname itself (rather than only pattern-matching it) so an attacker can't bypass
 * a literal-IP check by pointing a public-looking hostname's DNS record at an internal address.
 */
export async function assertSafeUrl(rawUrl: string): Promise<void> {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new EnrichmentError("Invalid URL");
  }

  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new EnrichmentError("Only http/https URLs are allowed");
  }

  const hostname = parsed.hostname;
  if (BLOCKED_HOSTNAMES.has(hostname.toLowerCase())) {
    throw new EnrichmentError("URL host is not allowed");
  }

  if (isIP(hostname)) {
    if (isPrivateIp(hostname)) {
      throw new EnrichmentError("URL resolves to a private/internal address");
    }
    return;
  }

  let records: { address: string }[];
  try {
    records = await dns.lookup(hostname, { all: true });
  } catch {
    throw new EnrichmentError("Could not resolve URL host");
  }
  if (records.some((r) => isPrivateIp(r.address))) {
    throw new EnrichmentError("URL resolves to a private/internal address");
  }
}
