import { Router, type Request, type Response } from "express";
import { requireAuth } from "../auth/middleware.js";
import { recordRequest } from "../metrics.js";
import { deleteUserAccount, exportUserData } from "./repo.js";

export const accountRouter = Router();

accountRouter.use(requireAuth);

accountRouter.get("/export", async (req: Request, res: Response) => {
  const start = Date.now();
  try {
    const dump = await exportUserData(req.uid!);
    console.log(`[GET /account/export] uid=${req.uid} latency=${Date.now() - start}ms`);
    recordRequest("GET /account/export", Date.now() - start, false);
    res.setHeader("Content-Disposition", "attachment; filename=aniki-export.json");
    res.json(dump);
  } catch (err) {
    console.log(`[GET /account/export] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`);
    recordRequest("GET /account/export", Date.now() - start, true);
    res.status(500).json({ error: "Export failed" });
  }
});

accountRouter.delete("/", async (req: Request, res: Response) => {
  const start = Date.now();
  try {
    await deleteUserAccount(req.uid!);
    console.log(`[DELETE /account] uid=${req.uid} latency=${Date.now() - start}ms`);
    recordRequest("DELETE /account", Date.now() - start, false);
    res.json({ ok: true });
  } catch (err) {
    console.log(`[DELETE /account] uid=${req.uid} error="${(err as Error).message}" latency=${Date.now() - start}ms`);
    recordRequest("DELETE /account", Date.now() - start, true);
    res.status(500).json({ error: "Account deletion failed" });
  }
});
