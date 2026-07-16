import { Router, type Request, type Response } from "express";
import { requireAuth } from "../auth/middleware.js";
import { logAndRecord } from "../metrics.js";
import { deleteUserAccount, exportUserData } from "./repo.js";

export const accountRouter = Router();

accountRouter.use(requireAuth);

accountRouter.get("/export", async (req: Request, res: Response) => {
  const start = Date.now();
  try {
    const dump = await exportUserData(req.uid!);
    logAndRecord(
      `[GET /account/export] uid=${req.uid} latency=${Date.now() - start}ms`,
      "GET /account/export",
      Date.now() - start,
      false
    );
    res.setHeader("Content-Disposition", "attachment; filename=aniki-export.json");
    res.json(dump);
  } catch (err) {
    logAndRecord(
      `[GET /account/export] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`,
      "GET /account/export",
      Date.now() - start,
      true
    );
    res.status(500).json({ error: "Export failed" });
  }
});

accountRouter.delete("/", async (req: Request, res: Response) => {
  const start = Date.now();
  try {
    await deleteUserAccount(req.uid!);
    logAndRecord(
      `[DELETE /account] uid=${req.uid} latency=${Date.now() - start}ms`,
      "DELETE /account",
      Date.now() - start,
      false
    );
    res.json({ ok: true });
  } catch (err) {
    logAndRecord(
      `[DELETE /account] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`,
      "DELETE /account",
      Date.now() - start,
      true
    );
    res.status(500).json({ error: "Account deletion failed" });
  }
});
