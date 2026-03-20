/**
 * K6 Patch Day Scenario (500 QPS)
 * Issue #562: Load Testing + Optimization
 *
 * PRIMARY ACCEPTANCE TEST
 * This scenario validates the acceptance criteria: p99 < 200ms at 500 QPS
 *
 * Test Profile:
 * - Sustained load: 500 QPS for 10 minutes
 * - Warmup phase: 2 minutes ramping from 10 to 500 QPS
 * - STRICT thresholds: p99 < 200ms (Issue #562 acceptance criteria)
 * - Error rate must be < 1%
 * - Tracks cache hit rates using lib/metrics.js
 *
 * Usage:
 *   k6 run load-test/k6/scenarios/patch-day.js
 *
 * Environment variables:
 *   BASE_URL           - Target URL (default: http://localhost:8080)
 *   WARMUP_DURATION    - Warmup duration in seconds (default: 120)
 *   SUSTAINED_DURATION - Sustained load duration in seconds (default: 600)
 *   PATCH_DAY_RPS      - Target QPS (default: 500)
 */

import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

import { config, getExpectationUrl, buildOptions } from '../lib/config.js';
import { randomSelect, getCommonHeaders, waitForHealthy, isCacheHit, getLatencyBucket } from '../lib/helpers.js';
import { strictThresholds } from '../thresholds.js';
import {
  cacheHits,
  cacheMisses,
  cacheHitRate,
  responseSize,
  apiRequestsByEndpoint,
  errorsByType,
  errorRate,
  recordCacheResult,
  recordApiResponse,
  recordError,
  recordLatencyBucket,
  recordIteration,
} from '../lib/metrics.js';

// ============================================
// Test Data
// ============================================

/**
 * Load test characters from data file
 * SharedArray ensures data is loaded once and shared across all VUs
 */
const testCharacters = new SharedArray('test-characters', function () {
  try {
    const data = open('../data/test-characters.json');
    return JSON.parse(data).characters;
  } catch (e) {
    console.error(`Failed to load test data: ${e.message}`);
    return [];
  }
});

// ============================================
// Test Configuration
// ============================================

/**
 * K6 configuration for patch day scenario
 * Two-phase execution: warmup + sustained load
 */
export const options = buildOptions({
  scenarios: {
    warmup: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 100,
      stages: [
        { duration: config.durations.warmup + 's', target: 500 }, // Ramp to 500 QPS over 2 min
      ],
      startTime: '0s',
      exec: 'warmup',
    },
    sustained: {
      executor: 'constant-arrival-rate',
      rate: 500, // 500 QPS constant
      timeUnit: '1s',
      duration: config.durations.sustained + 's', // 10 minutes
      preAllocatedVUs: 500,
      maxVUs: 1000,
      startTime: config.durations.warmup + 's', // Start after warmup
      exec: 'sustained',
    },
  },
  thresholds: strictThresholds,
});

// ============================================
// Setup Function
// ============================================

/**
 * Setup function runs once before the test
 * Waits for the application to be healthy
 */
export function setup() {
  console.log('========================================');
  console.log('Patch Day Scenario: 500 QPS');
  console.log('========================================');
  console.log(`Base URL: ${config.baseUrl}`);
  console.log(`Warmup Duration: ${config.durations.warmup}s (10 -> 500 QPS)`);
  console.log(`Sustained Duration: ${config.durations.sustained}s (500 QPS constant)`);
  console.log(`Test Characters: ${testCharacters.length}`);
  console.log('========================================');

  const healthy = waitForHealthy();
  if (!healthy) {
    console.error('Application health check failed - aborting test');
    throw new Error('Health check failed');
  }

  return {
    startTime: new Date().toISOString(),
    characterCount: testCharacters.length,
  };
}

// ============================================
// Teardown Function
// ============================================

/**
 * Telemetry export function
 * Exports detailed metrics to JSON file
 */
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const outputPath = `load-test/results/patch-day-${timestamp}.json`;

  // Calculate custom metrics
  const httpReqs = data.metrics.http_reqs || { counts: {} };
  const totalRequests = (httpReqs.counts?.['200'] || 0) + (httpReqs.counts?.['500'] || 0) + (httpReqs.counts?.['503'] || 0);
  const successRequests = httpReqs.counts?.['200'] || 0;
  const errorRequests = totalRequests - successRequests;
  const actualErrorRate = totalRequests > 0 ? (errorRequests / totalRequests * 100).toFixed(2) : '0.00';

  const duration = data.metrics.http_req_duration || { values: {} };
  const p99 = duration.values?.['p(99)']?.toFixed(2) || 'N/A';
  const p95 = duration.values?.['p(95)']?.toFixed(2) || 'N/A';
  const p90 = duration.values?.['p(90)']?.toFixed(2) || 'N/A';
  const avg = duration.values?.['avg']?.toFixed(2) || 'N/A';

  // Cache metrics
  const cacheHitCount = data.metrics.cache_hits?.counts?.['pass'] || 0;
  const cacheMissCount = data.metrics.cache_misses?.counts?.['pass'] || 0;
  const totalCacheOps = cacheHitCount + cacheMissCount;
  const actualCacheHitRate = totalCacheOps > 0 ? (cacheHitCount / totalCacheOps * 100).toFixed(2) : '0.00';

  // Check threshold compliance
  const p99Threshold = 200;
  const errorRateThreshold = 1.0;
  const passedP99 = parseFloat(p99) < p99Threshold;
  const passedErrorRate = parseFloat(actualErrorRate) < errorRateThreshold;
  const testPassed = passedP99 && passedErrorRate;

  const summary = {
    metadata: {
      scenario: 'patch-day',
      targetQPS: 500,
      testDuration: `${config.durations.warmup}s warmup + ${config.durations.sustained}s sustained`,
      timestamp: new Date().toISOString(),
      acceptanceCriteria: 'Issue #562: p99 < 200ms at 500 QPS',
    },
    results: {
      totalRequests,
      successRequests,
      errorRequests,
      errorRate: `${actualErrorRate}%`,
      passed: testPassed,
    },
    latency: {
      p99: `${p99}ms`,
      p95: `${p95}ms`,
      p90: `${p90}ms`,
      avg: `${avg}ms`,
    },
    cache: {
      hits: cacheHitCount,
      misses: cacheMissCount,
      total: totalCacheOps,
      hitRate: `${actualCacheHitRate}%`,
    },
    thresholds: {
      p99: {
        threshold: `${p99Threshold}ms`,
        actual: `${p99}ms`,
        passed: passedP99,
      },
      errorRate: {
        threshold: `${errorRateThreshold}%`,
        actual: `${actualErrorRate}%`,
        passed: passedErrorRate,
      },
    },
    conclusion: testPassed
      ? '✓ ACCEPTANCE CRITERIA MET: Issue #562 requirements satisfied'
      : '✗ ACCEPTANCE CRITERIA FAILED: Issue #562 requirements NOT met',
  };

  console.log('========================================');
  console.log('PATCH DAY SCENARIO RESULTS');
  console.log('========================================');
  console.log(`Total Requests: ${totalRequests}`);
  console.log(`Error Rate: ${actualErrorRate}% (threshold: ${errorRateThreshold}%)`);
  console.log(`P99 Latency: ${p99}ms (threshold: ${p99Threshold}ms)`);
  console.log(`P95 Latency: ${p95}ms`);
  console.log(`P90 Latency: ${p90}ms`);
  console.log(`Cache Hit Rate: ${actualCacheHitRate}%`);
  console.log('========================================');
  console.log(summary.conclusion);
  console.log('========================================');
  console.log(`Results exported to: ${outputPath}`);

  return {
    stdout: JSON.stringify(summary, null, 2),
    [outputPath]: JSON.stringify(data, null, 2),
  };
}

// ============================================
// Scenario Functions
// ============================================

/**
 * Warmup scenario
 * Ramps from 10 to 500 QPS over 2 minutes
 * Allows cache warming and connection pool initialization
 */
export function warmup(data) {
  if (testCharacters.length === 0) {
    console.error('No test characters available');
    return;
  }

  const character = randomSelect(testCharacters);
  const ign = character.ignEncoded;
  const url = getExpectationUrl(ign);

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { scenario: 'warmup' },
    timeout: config.timeouts.request + 'ms',
  });

  // Track metrics
  const success = check(response, {
    'warmup status is 200': (r) => r.status === 200,
    'warmup has valid JSON': (r) => {
      try {
        r.json();
        return true;
      } catch (e) {
        return false;
      }
    },
    'warmup response time < 500ms': (r) => r.timings.duration < 500,
  });

  // Record custom metrics
  const cacheHit = isCacheHit(response);
  recordCacheResult(cacheHit, 0);
  recordApiResponse('/api/v4/characters/{ign}/expectation', response.body.length, 0);
  recordLatencyBucket(response.timings.duration);
  recordIteration();

  if (!success) {
    recordError('warmup', response.status);
  }
}

/**
 * Sustained load scenario
 * Maintains constant 500 QPS for 10 minutes
 * This is the primary acceptance test phase
 */
export function sustained(data) {
  if (testCharacters.length === 0) {
    console.error('No test characters available');
    return;
  }

  const character = randomSelect(testCharacters);
  const ign = character.ignEncoded;
  const url = getExpectationUrl(ign);

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { scenario: 'sustained' },
    timeout: config.timeouts.request + 'ms',
  });

  // Track metrics with STRICT thresholds
  const success = check(response, {
    'sustained status is 200': (r) => r.status === 200,
    'sustained has valid JSON': (r) => {
      try {
        r.json();
        return true;
      } catch (e) {
        return false;
      }
    },
    'sustained response time < 200ms (p99 threshold)': (r) => r.timings.duration < 200,
  });

  // Record custom metrics
  const cacheHit = isCacheHit(response);
  recordCacheResult(cacheHit, 0);
  recordApiResponse('/api/v4/characters/{ign}/expectation', response.body.length, 0);
  recordLatencyBucket(response.timings.duration);
  recordIteration();

  if (!success) {
    recordError('sustained', response.status);
  }

  // Log slow requests for investigation
  if (response.timings.duration > 200) {
    console.warn(`Slow request detected: ${response.timings.duration.toFixed(2)}ms for IGN: ${ign}`);
  }
}
