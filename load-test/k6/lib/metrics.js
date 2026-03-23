/**
 * K6 Custom Metrics
 * Issue #562: Load Testing + Optimization
 *
 * Custom metrics for tracking application-specific performance indicators.
 */

import { Counter, Gauge, Trend, Rate } from 'k6/metrics';

// ============================================
// Cache Metrics
// ============================================

/**
 * Cache hit counter
 */
export const cacheHits = new Counter('cache_hits');

/**
 * Cache miss counter
 */
export const cacheMisses = new Counter('cache_misses');

/**
 * Cache hit rate (0-1)
 */
export const cacheHitRate = new Rate('cache_hit_rate');

/**
 * Cache latency trend (time to retrieve from cache)
 */
export const cacheLatency = new Trend('cache_latency_ms');

// ============================================
// Database Metrics
// ============================================

/**
 * Database query duration trend
 */
export const dbQueryDuration = new Trend('db_query_duration_ms');

/**
 * Database connection wait time
 */
export const dbConnectionWait = new Trend('db_connection_wait_ms');

/**
 * Database query counter
 */
export const dbQueryCount = new Counter('db_queries');

// ============================================
// API Metrics
// ============================================

/**
 * API response size trend
 */
export const responseSize = new Trend('response_size_bytes');

/**
 * Expectation calculation time
 */
export const expectationCalcTime = new Trend('expectation_calc_time_ms');

/**
 * API endpoint counter by type
 */
export const apiRequestsByEndpoint = new Counter('api_requests_by_endpoint');

// ============================================
// Error Metrics
// ============================================

/**
 * Error counter by type
 */
export const errorsByType = new Counter('errors_by_type');

/**
 * Error rate
 */
export const errorRate = new Rate('error_rate');

/**
 * Timeout counter
 */
export const timeouts = new Counter('timeouts');

// ============================================
// Latency Distribution
// ============================================

/**
 * Latency buckets for histogram
 */
export const latencyBuckets = {
  under50ms: new Counter('latency_under_50ms'),
  under100ms: new Counter('latency_under_100ms'),
  under200ms: new Counter('latency_under_200ms'),
  under500ms: new Counter('latency_under_500ms'),
  under1s: new Counter('latency_under_1s'),
  over1s: new Counter('latency_over_1s'),
};

// ============================================
// Virtual User Metrics
// ============================================

/**
 * Active VUs gauge
 */
export const activeVus = new Gauge('active_vus');

/**
 * VU iterations completed
 */
export const iterationsCompleted = new Counter('iterations_completed');

// ============================================
// Helper Functions
// ============================================

/**
 * Record cache result
 * @param {boolean} hit - Whether it was a cache hit
 * @param {number} latencyMs - Cache retrieval latency in ms
 */
export function recordCacheResult(hit, latencyMs = 0) {
  if (hit) {
    cacheHits.add(1);
    cacheHitRate.add(1);
  } else {
    cacheMisses.add(1);
    cacheHitRate.add(0);
  }

  if (latencyMs > 0) {
    cacheLatency.add(latencyMs);
  }
}

/**
 * Record database query
 * @param {number} durationMs - Query duration in ms
 * @param {number} connectionWaitMs - Connection wait time in ms
 */
export function recordDbQuery(durationMs, connectionWaitMs = 0) {
  dbQueryDuration.add(durationMs);
  dbQueryCount.add(1);

  if (connectionWaitMs > 0) {
    dbConnectionWait.add(connectionWaitMs);
  }
}

/**
 * Record API response
 * @param {string} endpoint - Endpoint name
 * @param {number} size - Response size in bytes
 * @param {number} calcTimeMs - Calculation time in ms (optional)
 */
export function recordApiResponse(endpoint, size, calcTimeMs = 0) {
  apiRequestsByEndpoint.add(1, { endpoint: endpoint });
  responseSize.add(size);

  if (calcTimeMs > 0) {
    expectationCalcTime.add(calcTimeMs);
  }
}

/**
 * Record error
 * @param {string} type - Error type
 * @param {number} status - HTTP status code (optional)
 */
export function recordError(type, status = 0) {
  errorsByType.add(1, { type: type, status: String(status) });
  errorRate.add(1);
}

/**
 * Record latency in appropriate bucket
 * @param {number} durationMs - Duration in milliseconds
 */
export function recordLatencyBucket(durationMs) {
  if (durationMs < 50) {
    latencyBuckets.under50ms.add(1);
  } else if (durationMs < 100) {
    latencyBuckets.under100ms.add(1);
  } else if (durationMs < 200) {
    latencyBuckets.under200ms.add(1);
  } else if (durationMs < 500) {
    latencyBuckets.under500ms.add(1);
  } else if (durationMs < 1000) {
    latencyBuckets.under1s.add(1);
  } else {
    latencyBuckets.over1s.add(1);
  }
}

/**
 * Record iteration completion
 */
export function recordIteration() {
  iterationsCompleted.add(1);
}

/**
 * Update active VUs gauge
 * @param {number} count - Number of active VUs
 */
export function updateActiveVus(count) {
  activeVus.set(count);
}

export default {
  // Cache metrics
  cacheHits,
  cacheMisses,
  cacheHitRate,
  cacheLatency,

  // DB metrics
  dbQueryDuration,
  dbConnectionWait,
  dbQueryCount,

  // API metrics
  responseSize,
  expectationCalcTime,
  apiRequestsByEndpoint,

  // Error metrics
  errorsByType,
  errorRate,
  timeouts,

  // Latency buckets
  latencyBuckets,

  // VU metrics
  activeVus,
  iterationsCompleted,

  // Helpers
  recordCacheResult,
  recordDbQuery,
  recordApiResponse,
  recordError,
  recordLatencyBucket,
  recordIteration,
  updateActiveVus,
};
