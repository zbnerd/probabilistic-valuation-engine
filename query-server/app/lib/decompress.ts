// query-server/app/lib/decompress.ts
import { gunzipSync } from "zlib";

export class GzipDecompressionError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "GzipDecompressionError";
  }
}

// Decompression bomb protection
const MAX_COMPRESSED_BYTES = parseInt(process.env.MAX_COMPRESSED_BYTES ?? "1000000", 10); // 1MB default
const MAX_DECOMPRESSED_BYTES = parseInt(process.env.MAX_DECOMPRESSED_BYTES ?? "10000000", 10); // 10MB default

export function decompressPayload(payload: Buffer): string {
  if (!Buffer.isBuffer(payload) || payload.length < 2) {
    throw new GzipDecompressionError("Payload too short or not a Buffer");
  }
  if (payload.length > MAX_COMPRESSED_BYTES) {
    throw new GzipDecompressionError(`Compressed payload too large: ${payload.length} bytes`);
  }
  if (payload[0] !== 0x1f || payload[1] !== 0x8b) {
    throw new GzipDecompressionError("Invalid GZIP magic number");
  }
  try {
    // maxOutputLength makes gunzipSync throw early if output exceeds limit, BEFORE allocating all memory
    const decompressed = gunzipSync(payload, { maxOutputLength: MAX_DECOMPRESSED_BYTES });
    return decompressed.toString("utf-8");
  } catch (error) {
    if (error instanceof GzipDecompressionError) throw error;
    throw new GzipDecompressionError(`GZIP decompression failed: ${error instanceof Error ? error.message : String(error)}`);
  }
}

export function isExpired(dbNow: Date, calculatedAt: Date, ttlMinutes: number): boolean {
  const expiresAt = new Date(calculatedAt.getTime() + ttlMinutes * 60_000);
  return dbNow > expiresAt;
}
