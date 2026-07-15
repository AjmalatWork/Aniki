import { Pool, types } from "pg";
import { config } from "../config.js";

// node-postgres returns BIGINT (oid 20) as strings by default since it can't guarantee they fit
// in a JS number. Our BIGINTs are epoch-millis and seq counters, both far under
// Number.MAX_SAFE_INTEGER, so parsing them as numbers everywhere is safe and much less error
// prone than remembering to Number() every updated_at/deleted_at/seq column by hand.
types.setTypeParser(20, (value: string) => Number(value));

export const pool = new Pool({ connectionString: config.databaseUrl });
