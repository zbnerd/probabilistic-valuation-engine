/**
 * K6 Viral Spike Scenario
 * Issue #562: Load Testing + Optimization
 *
 * Tests system resilience under extreme viral traffic conditions.
 * Simulates a viral content spike where traffic ramps from 100 to 2000 QPS.
 *
 * Ramp Stages:
 * 1. Warm-up: 100 → 1000 QPS over 1 minute
 * 2. Ramp up: 1000 → 2000 QPS over 1 minute
 * 3. Peak: 2000 QPS sustained for 1 minute
 * 4. Ramp down: 2000 → 100 QPS over 2 minutes
 *
 * Total Duration: 5 minutes
 * Thresholds: Relaxed (p99 < 1000ms, < 10% error rate)
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';

// Import shared configuration and utilities
import { config, getExpectationUrl } from '../lib/config.js';
import {
  randomSelect,
  getCommonHeaders,
  checkResponse,
  isCacheHit,
  getLatencyBucket,
  formatDuration,
} from '../lib/helpers.js';

// Import metrics
import {
  recordCacheResult,
  recordApiResponse,
  recordError,
  recordLatencyBucket,
  updateActiveVus,
  cacheHits,
  cacheMisses,
} from '../lib/metrics';

// Import thresholds
import { viralSpikeThresholds } from '../thresholds.js';

/**
 * Load test data - shared across all VUs
 */
const testData = new SharedArray('testCharacters', function () {
  // Load character data from JSON file
  const data = open('../data/test-characters.json');
  return JSON.parse(data).characters;
});

/**
 * K6 Configuration
 */
export const options = {
  scenarios: {
    viral_spike: {
      executor: 'ramping-arrival-rate',
      startRate: 100, // Start with 100 iterations per second
      timeUnit: '1s',
      preAllocatedVUs: 500, // Pre-allocate VUs for smooth ramping
      maxVUs: 2500, // Maximum VUs for peak load
      stages: [
        // Warm-up: 100 → 1000 QPS over 1 minute
        { duration: '1m', target: 1000 },
        // Ramp up: 1000 → 2000 QPS over 1 minute
        { duration: '1m', target: 2000 },
        // Peak: Sustain 2000 QPS for 1 minute
        { duration: '1m', target: 2000 },
        // Ramp down: 2000 → 100 QPS over 2 minutes
        { duration: '2m', target: 100 },
      ],
      gracefulRampDown: '30s', // Allow graceful shutdown
    },
  },

  // Use relaxed thresholds for stress testing
  thresholds: {
    ...viralSpikeThresholds,
    // Custom cache hit rate thresholds
    cache_hits: ['count>0'],
    cache_misses: ['count>0'],
  },

  // Connection settings
  noConnectionReuse: false, // Allow connection reuse for better performance
  discardResponseBodies: false, // Keep bodies for validation
  userAgent: 'K6-ViralSpike/1.0 (MapleExpectation Issue #562)',
};

/**
 * Setup function - runs once before test
 */
export function setup() {
  console.log('='.repeat(60));
  console.log('Viral Spike Scenario Starting');
  console.log('='.repeat(60));
  console.log(`Target: ${config.baseUrl}`);
  console.log(`Test Characters: ${testData.length}`);
  console.log(`Max Rate: 2000 QPS`);
  console.log(`Total Duration: 5 minutes`);
  console.log(`Thresholds: p99 < 1000ms, < 10% error rate`);
  console.log('='.repeat(60));

  // Verify application is ready
  const healthUrl = `${config.baseUrl}/actuator/health`;
  const maxRetries = 30;

  for (let i = 0; i < maxRetries; i++) {
    const response = http.get(healthUrl, {
      headers: getCommonHeaders(),
      timeout: '5s',
    });

    if (response.status === 200) {
      console.log(`Application is healthy after ${i + 1} attempts`);
      return { healthy: true };
    }

    console.log(`Health check attempt ${i + 1}/${maxRetries} failed`);
    sleep(1);
  }

  console.error('Application health check failed - aborting test');
  return { healthy: false };
}

/**
 * Main VU function - runs continuously
 */
export default function (data) {
  // Select a random character from test data
  const character = randomSelect(testData);
  const ign = character.ignEncoded;

  // Build the expectation API URL
  const url = getExpectationUrl(ign);

  // Make the request
  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: 'GetExpectation' },
    timeout: '30s',
  });

  // Record cache hit/miss
  const cacheHit = isCacheHit(response);
  recordCacheResult(cacheHit, response.timings.duration);

  // Record API response metrics
  recordApiResponse('expectation', response.body.length, 0);

  // Record latency bucket
  recordLatencyBucket(response.timings.duration);

  // Update active VUs gauge
  updateActiveVus(__VU);

  // Check response validation
  const checks = checkResponse(response, 'GetExpectation');

  // Record errors if validation failed
  if (!checks) {
    recordError('validation_failed', response.status);
  }

  // Optional: Small sleep to prevent overwhelming
  // Note: With ramping-arrival-rate executor, sleep is not strictly needed
  // as the executor controls the iteration rate.
  // However, we add minimal jitter to simulate realistic traffic patterns.
  sleep(Math.random() * 0.01); // 0-10ms random delay
}

/**
 * Teardown function - runs once after test
 */
export function teardown(data) {
  console.log('='.repeat(60));
  console.log('Viral Spike Scenario Completed');
  console.log('='.repeat(60));

  if (!data.healthy) {
    console.error('Test completed with unhealthy application');
  }
}

/**
 * Custom summary handler for detailed reporting
 */
export function handleSummary(data) {
  console.log('\n' + '='.repeat(60));
  console.log('VIRAL SPIKE TEST RESULTS');
  console.log('='.repeat(60));

  // Extract key metrics
  const httpMetrics = data.metrics.http_req_duration;
  const httpErrors = data.metrics.http_req_failed;
  const totalRequests = data.metrics.http_reqs;
  const cacheHitsCount = data.metrics.cache_hits?.values?.count || 0;
  const cacheMissesCount = data.metrics.cache_misses?.values?.count || 0;

  // Calculate cache hit rate
  const totalCacheOps = cacheHitsCount + cacheMissesCount;
  const cacheHitRate = totalCacheOps > 0 ? (cacheHitsCount / totalCacheOps * 100).toFixed(2) : '0.00';

  // Print summary
  console.log('\n📊 Performance Summary:');
  console.log(`  Total Requests: ${totalRequests?.values?.count || 0}`);
  console.log(`  P50 Latency: ${(httpMetrics?.values?.p(50) || 0).toFixed(2)}ms`);
  console.log(`  P90 Latency: ${(httpMetrics?.values?.p(90) || 0).toFixed(2)}ms`);
  console.log(`  P95 Latency: ${(httpMetrics?.values?.p(95) || 0).toFixed(2)}ms`);
  console.log(`  P99 Latency: ${(httpMetrics?.values?.p(99) || 0).toFixed(2)}ms`);
  console.log(`  Max Latency: ${(httpMetrics?.values?.max || 0).toFixed(2)}ms`);

  console.log('\n❌ Error Analysis:');
  console.log(`  Failed Requests: ${totalRequests?.values?.failed || 0}`);
  console.log(`  Error Rate: ${((httpErrors?.values?.rate || 0) * 100).toFixed(2)}%`);

  console.log('\n💾 Cache Performance:');
  console.log(`  Cache Hits: ${cacheHitsCount}`);
  console.log(`  Cache Misses: ${cacheMissesCount}`);
  console.log(`  Cache Hit Rate: ${cacheHitRate}%`);

  // Threshold validation
  console.log('\n✅ Threshold Validation:');

  const p99 = httpMetrics?.values?.p(99) || 0;
  const p99Threshold = 1000;
  const p99Passed = p99 < p99Threshold;

  const errorRate = httpErrors?.values?.rate || 0;
  const errorThreshold = 0.10;
  const errorPassed = errorRate < errorThreshold;

  console.log(`  P99 Latency: ${p99.toFixed(2)}ms ${p99Passed ? '✅' : '❌'} (threshold: ${p99Threshold}ms)`);
  console.log(`  Error Rate: ${(errorRate * 100).toFixed(2)}% ${errorPassed ? '✅' : '❌'} (threshold: ${(errorThreshold * 100).toFixed(0)}%)`);

  const overallPassed = p99Passed && errorPassed;
  console.log('\n' + '='.repeat(60));
  console.log(`Overall Result: ${overallPassed ? '✅ PASSED' : '❌ FAILED'}`);
  console.log('='.repeat(60) + '\n');

  // Generate JSON output for CI/CD integration
  return {
    'viral-spike-summary.json': JSON.stringify({
      scenario: 'viral-spike',
      timestamp: new Date().toISOString(),
      passed: overallPassed,
      metrics: {
        totalRequests: totalRequests?.values?.count || 0,
        failedRequests: totalRequests?.values?.failed || 0,
        latency: {
          p50: httpMetrics?.values?.p(50) || 0,
          p90: httpMetrics?.values?.p(90) || 0,
          p95: httpMetrics?.values?.p(95) || 0,
          p99: httpMetrics?.values?.p(99) || 0,
          max: httpMetrics?.values?.max || 0,
        },
        errorRate: errorRate,
        cache: {
          hits: cacheHitsCount,
          misses: cacheMissesCount,
          hitRate: parseFloat(cacheHitRate),
        },
      },
      thresholds: {
        p99Latency: { value: p99, threshold: p99Threshold, passed: p99Passed },
        errorRate: { value: errorRate, threshold: errorThreshold, passed: errorPassed },
      },
    }, null, 2),
  };
}
