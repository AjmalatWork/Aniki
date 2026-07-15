import { readFileSync } from "node:fs";
import type { NextFunction, Request, Response } from "express";
import { cert, getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { config } from "../config.js";
import { ensureUser } from "../sync/repo.js";

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      uid?: string;
      userEmail?: string | null;
    }
  }
}

function initFirebaseAdmin(): void {
  if (getApps().length > 0) return;
  if (!config.firebaseServiceAccountPath) {
    throw new Error(
      "FIREBASE_SERVICE_ACCOUNT_PATH is not set. Either provide it or set AUTH_DEV_BYPASS=true for local dev."
    );
  }
  const serviceAccount = JSON.parse(readFileSync(config.firebaseServiceAccountPath, "utf-8"));
  initializeApp({ credential: cert(serviceAccount) });
}

/**
 * Verifies the Firebase ID token on every /sync request and derives uid from it — the uid is
 * NEVER trusted from the request body. In AUTH_DEV_BYPASS mode (local dev only, before a
 * Firebase project exists), an X-Debug-Uid header stands in for a verified token.
 */
export async function requireAuth(req: Request, res: Response, next: NextFunction): Promise<void> {
  try {
    let uid: string;
    let email: string | null;

    if (config.authDevBypass) {
      const debugUid = req.header("X-Debug-Uid");
      if (!debugUid) {
        res.status(401).json({ error: "Missing X-Debug-Uid header (AUTH_DEV_BYPASS mode)" });
        return;
      }
      uid = debugUid;
      email = null;
    } else {
      const authHeader = req.header("Authorization");
      const token = authHeader?.startsWith("Bearer ") ? authHeader.slice("Bearer ".length) : null;
      if (!token) {
        res.status(401).json({ error: "Missing Authorization: Bearer <idToken> header" });
        return;
      }
      initFirebaseAdmin();
      const decoded = await getAuth().verifyIdToken(token);
      uid = decoded.uid;
      email = decoded.email ?? null;
    }

    await ensureUser(uid, email);
    req.uid = uid;
    req.userEmail = email;
    next();
  } catch (err) {
    res.status(401).json({ error: `Invalid or expired token: ${(err as Error).message}` });
  }
}
