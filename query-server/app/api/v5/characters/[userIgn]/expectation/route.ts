// query-server/app/api/v5/characters/[userIgn]/expectation/route.ts
import { NextRequest, NextResponse } from "next/server";
import { query, DatabaseConnectionError, QueryTimeoutError } from "@/app/lib/db";
import { decompressPayload, isExpired, GzipDecompressionError } from "@/app/lib/decompress";

const CACHE_TTL_SECONDS = parseInt(process.env.CACHE_TTL_SECONDS ?? "3600", 10);
const MAX_STALE_SECONDS = parseInt(process.env.MAX_STALE_SECONDS ?? "5", 10);

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ userIgn: string }> },
): Promise<NextResponse> {
  const { userIgn } = await params;

  // Input validation
  if (!userIgn || userIgn.length === 0 || userIgn.length > 100) {
    return NextResponse.json(
      { status: "error", code: "INVALID_USER_IGN" },
      { status: 400 },
    );
  }

  // Request ID for cross-service tracing
  const requestId = request.headers.get("x-request-id")
    ?? crypto.randomUUID();

  try {
    const result = await query(
      `SELECT payload, calculated_at,
              NOW() as db_now,
              EXTRACT(EPOCH FROM (NOW() - calculated_at)) as age_seconds
       FROM character_expectation_read_model WHERE user_ign = $1`,
      [userIgn],
    );

    if (result.rows.length === 0) {
      return NextResponse.json({ status: "pending" }, { status: 202 });
    }

    const row = result.rows[0];

    // Stale check: TTL + MAX_STALE_SECONDS threshold
    const maxAgeSeconds = CACHE_TTL_SECONDS + MAX_STALE_SECONDS;
    if (row.age_seconds > maxAgeSeconds) {
      return NextResponse.json(
        { status: "error", code: "REPLICA_STALE", retryable: true },
        { status: 503 },
      );
    }

    // TTL expiry check using DB time
    const ttlMinutes = Math.ceil(CACHE_TTL_SECONDS / 60);
    if (isExpired(row.db_now, row.calculated_at, ttlMinutes)) {
      return NextResponse.json({ status: "pending" }, { status: 202 });
    }

    const json = decompressPayload(row.payload);

    return new NextResponse(json, {
      headers: {
        "Content-Type": "application/json",
        "X-Request-ID": requestId,
      },
    });
  } catch (error) {
    // Self-heal: delete corrupted payload on GZIP error
    if (error instanceof GzipDecompressionError) {
      try {
        await query(
          'DELETE FROM character_expectation_read_model WHERE user_ign = $1',
          [userIgn],
        );
      } catch (cleanupError) {
        console.error('[DB] Failed to cleanup corrupted payload:', cleanupError);
      }
      return NextResponse.json(
        { status: "pending", code: "PAYLOAD_CORRUPTED" },
        { status: 202 },
      );
    }

    if (error instanceof DatabaseConnectionError) {
      return NextResponse.json(
        { status: "error", code: "DATABASE_UNAVAILABLE", retryable: true },
        { status: 503 },
      );
    }

    if (error instanceof QueryTimeoutError) {
      return NextResponse.json(
        { status: "error", code: "QUERY_TIMEOUT", retryable: true },
        { status: 504 },
      );
    }

    return NextResponse.json(
      { status: "error", code: "UNKNOWN_ERROR" },
      { status: 500 },
    );
  }
}
