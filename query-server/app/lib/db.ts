// query-server/app/lib/db.ts
import { Pool } from "pg";

// Custom error types for classification
export class DatabaseConnectionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "DatabaseConnectionError";
  }
}

export class QueryTimeoutError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "QueryTimeoutError";
  }
}

// Pool recreation for serverless environments
let pool: Pool | null = null;

function getPool(): Pool {
  if (!process.env.REPLICA_DATABASE_URL) {
    throw new Error("REPLICA_DATABASE_URL environment variable is required");
  }

  if (!pool) {
    pool = new Pool({
      connectionString: process.env.REPLICA_DATABASE_URL,
      max: 2,
      idleTimeoutMillis: 10000,
      connectionTimeoutMillis: 10000,
      statement_timeout: 5000,
      query_timeout: 5000,
    });

    pool.on("error", (err) => {
      console.error("[DB] Pool error, destroying pool:", err.message);
      pool?.end().catch(() => {});
      pool = null;
    });
  }
  return pool;
}

// NOTE: For production, use PgBouncer between Vercel and Replica
// to prevent connection exhaustion under high concurrency.

export async function query(text: string, params: unknown[]) {
  const start = Date.now();
  try {
    const result = await getPool().query(text, params);
    const duration = Date.now() - start;
    if (duration > 1000) {
      console.warn(`[DB] Slow query: ${duration}ms`);
    }
    return result;
  } catch (error) {
    console.error("[DB] Query failed:", error instanceof Error ? error.message : String(error));

    if (error instanceof Error) {
      if (error.message.includes("timeout") || error.message.includes("statement timeout")) {
        throw new QueryTimeoutError(error.message);
      }
      if (error.message.includes("connection") || error.message.includes("ECONNREFUSED")) {
        throw new DatabaseConnectionError(error.message, error);
      }
    }
    throw error;
  }
}

// Graceful shutdown (development only)
if (process.env.NODE_ENV === "development") {
  process.on("beforeExit", async () => {
    if (pool) { await pool.end(); pool = null; }
  });
}
