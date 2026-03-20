/**
 * K6 Normal Traffic Scenario
 * Issue #562: Load Testing + Optimization
 *
 * A minimal load scenario (0.5 QPS) to validate system behavior under normal conditions.
 * This provides a baseline for comparing against stress tests and validates caching behavior.
 */

import { check, sleep } from 'k6';
import http from 'k6/http';
import { SharedArray } from 'k6/data';

// Import shared configuration and utilities
import { config, getExpectationUrl, buildOptions } from '../lib/config.js';
import { randomSelect, checkResponse, isCacheHit, getLatencyBucket } from '../lib/helpers.js';
import {
  cacheHits,
  cacheMisses,
  recordCacheResult,
  recordApiResponse,
  recordLatencyBucket,
  recordError,
  recordIteration,
  updateActiveVus,
} from '../lib/metrics.js';

// Load test data (shared across all VUs)
const testData = new SharedArray('testCharacters', function () {
  return JSON.parse(open('../data/test-characters.json')).characters;
});

// Scenario configuration
export const options = buildOptions({
  scenarios: {
    normal_traffic: {
      executor: 'constant-arrival-rate',
      rate: config.rps.normal, // 0.5 requests per second
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 10,
      maxVUs: 10,
    },
  },
  thresholds: {
    // Relaxed thresholds for normal traffic
    http_req_duration: ['p(99)<300'], // 99th percentile under 300ms
    http_req_failed: ['rate<0.001'],  // Less than 0.1% error rate
    // Custom metrics thresholds
    cache_hit_rate: ['rate>0.7'],     // Expect 70%+ cache hit rate
  },
});

/**
 * Setup function - runs once before the test
 * Performs health check and warmup
 */
export function setup() {
  console.log('=== Normal Traffic Scenario Setup ===');
  console.log(`Target URL: ${config.baseUrl}`);
  console.log(`Rate: ${config.rps.normal} QPS`);
  console.log(`Duration: 5m`);
  console.log(`Max VUs: 10`);

  // Perform health check
  const healthUrl = `${config.baseUrl}/actuator/health`;
  const maxRetries = 30;

  for (let i = 0; i < maxRetries; i++) {
    const response = http.get(healthUrl, {
      timeout: '5s',
      headers: { 'Accept': 'application/json' },
    });

    if (response.status === 200) {
      console.log(`Health check passed after ${i + 1} attempts`);
      break;
    }

    if (i === maxRetries - 1) {
      throw new Error('Health check failed - application not ready');
    }

    sleep(1);
  }

  // Warmup phase - send initial requests to populate caches
  console.log('Starting 30-second warmup phase...');
  const warmupDuration = 30;
  const warmupStart = new Date();

  while ((new Date() - warmupStart) / 1000 < warmupDuration) {
    const character = randomSelect(testData);
    const url = getExpectationUrl(character.ignEncoded);

    http.get(url, {
      headers: { 'Accept': 'application/json' },
      timeout: '10s',
    });

    sleep(Math.random() * 2 + 1); // 1-3 second intervals
  }

  console.log('Warmup complete, starting normal traffic test...');
  return { startTime: new Date().toISOString() };
}

/**
 * Main scenario function - runs for each iteration
 */
export default function (data) {
  // Update active VUs metric
  updateActiveVus(__VU);

  // Select a random character from test data
  const character = randomSelect(testData);
  const url = getExpectationUrl(character.ignEncoded);

  // Send request
  const response = http.get(url, {
    headers: {
      'Accept': 'application/json',
      'Accept-Encoding': 'gzip, deflate',
      'Content-Type': 'application/json',
    },
    tags: { name: 'expectation_api' },
    timeout: '30s',
  });

  // Validate response
  const isValid = check(response, {
    'status is 200': (r) => r.status === 200,
    'has valid JSON': (r) => {
      try {
        r.json();
        return true;
      } catch (e) {
        return false;
      }
    },
    'response time < 300ms': (r) => r.timings.duration < 300,
  });

  // Record metrics
  const durationMs = response.timings.duration;
  const cacheHit = isCacheHit(response);

  recordCacheResult(cacheHit);
  recordApiResponse('expectation', response.body.length, 0);
  recordLatencyBucket(durationMs);

  if (!isValid) {
    recordError('validation_failed', response.status);
  }

  // Log slow requests
  if (durationMs > 300) {
    console.warn(`Slow request: ${character.ign} - ${durationMs.toFixed(2)}ms`);
  }

  // Small pause to avoid overwhelming the system
  sleep(Math.random() * 0.5 + 0.5); // 0.5-1 second pause

  // Record iteration completion
  recordIteration();
}

/**
 * Teardown function - runs once after the test
 */
export function teardown(data) {
  console.log('=== Normal Traffic Scenario Complete ===');
  console.log(`Started at: ${data.startTime}`);
  console.log(`Completed at: ${new Date().toISOString()}`);
}

/**
 * Handle summary output
 * Generates JSON report with key metrics
 */
export function handleSummary(data) {
  const summary = {
    scenario: 'normal-traffic',
    timestamp: new Date().toISOString(),
    config: {
      rate: config.rps.normal,
      duration: '5m',
      maxVUs: 10,
    },
    metrics: {
      // Request metrics
      totalRequests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
      failedRequests: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.passes : 0,
      errorRate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0,

      // Latency metrics
      p50: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(50)'] : 0,
      p90: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(90)'] : 0,
      p95: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'] : 0,
      p99: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(99)'] : 0,
      avg: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg : 0,
      min: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.min : 0,
      max: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.max : 0,

      // Cache metrics
      cacheHits: data.metrics.cache_hits ? data.metrics.cache_hits.values.count : 0,
      cacheMisses: data.metrics.cache_misses ? data.metrics.cache_misses.values.count : 0,
      cacheHitRate: data.metrics.cache_hit_rate ? data.metrics.cache_hit_rate.values.rate : 0,

      // VU metrics
      vusMax: data.metrics.vus ? data.metrics.vus.values.max : 0,
      vusMin: data.metrics.vus ? data.metrics.vus.values.min : 0,
    },
    thresholds: {
      http_req_duration: data.metrics.http_req_duration && data.metrics.http_req_duration.thresholds
        ? data.metrics.http_req_duration.thresholds['p(99)<300']
        : null,
      http_req_failed: data.metrics.http_req_failed && data.metrics.http_req_failed.thresholds
        ? data.metrics.http_req_failed.thresholds['rate<0.001']
        : null,
      cache_hit_rate: data.metrics.cache_hit_rate && data.metrics.cache_hit_rate.thresholds
        ? data.metrics.cache_hit_rate.thresholds['rate>0.7']
        : null,
    },
  };

  console.log('\n=== Normal Traffic Test Summary ===');
  console.log(`Total Requests: ${summary.metrics.totalRequests}`);
  console.log(`Error Rate: ${(summary.metrics.errorRate * 100).toFixed(3)}%`);
  console.log(`P50 Latency: ${summary.metrics.p50.toFixed(2)}ms`);
  console.log(`P99 Latency: ${summary.metrics.p99.toFixed(2)}ms`);
  console.log(`Cache Hit Rate: ${(summary.metrics.cacheHitRate * 100).toFixed(1)}%`);

  // Return summary object for K6 to output
  return {
    'load-test/results/normal-traffic-summary.json': JSON.stringify(summary, null, 2),
    stdout: JSON.stringify(summary, null, 2),
  };
}
