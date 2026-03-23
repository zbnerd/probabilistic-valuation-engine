/**
 * K6 Main Entry Point
 * Issue #562: Load Testing + Optimization
 *
 * Main entry point for all load test scenarios.
 * Use --env SCENARIO=scenario-name to select a scenario.
 *
 * Available scenarios:
 * - quick: Quick smoke test (30s)
 * - normal: Normal traffic pattern
 * - patch-day: Patch day load test (500 QPS)
 * - viral-spike: Viral traffic spike (2000 QPS)
 * - mixed: Mixed workload with realistic traffic distribution
 *
 * Usage:
 *   k6 run --env SCENARIO=normal main.js
 *   k6 run --env SCENARIO=patch-day --env DURATION=600 main.js
 *   k6 run --env SCENARIO=mixed main.js
 */

import http from 'k6/http';
import { check } from 'k6';
import { config, getHealthUrl } from './lib/config.js';
import { waitForHealthy, getCommonHeaders } from './lib/helpers.js';
import { getThresholdsForScenario } from './thresholds.js';
import * as metrics from './lib/metrics.js';

// Scenario selection from environment variable
const SCENARIO = __ENV.SCENARIO || 'quick';
const DURATION = parseInt(__ENV.DURATION || '0');

// Scenario configurations
export const scenarios = {
  quick: {
    executor: 'constant-vus',
    vus: 5,
    duration: '30s',
    exec: 'quickTest',
    gracefulStop: '5s',
  },

  normal: {
    executor: 'ramping-vus',
    startVUs: 10,
    stages: [
      { duration: '30s', target: 50 }, // Ramp up
      { duration: '2m', target: 50 }, // Sustained
      { duration: '30s', target: 10 }, // Ramp down
    ],
    exec: 'normalTraffic',
    gracefulStop: '30s',
  },

  'patch-day': {
    executor: 'constant-arrival-rate',
    rate: 500, // 500 RPS
    timeUnit: '1s',
    preAllocatedVUs: 100,
    maxVUs: 500,
    exec: 'patchDayTraffic',
    gracefulStop: '1m',
  },

  'viral-spike': {
    executor: 'ramping-arrival-rate',
    startRate: 100,
    timeUnit: '1s',
    preAllocatedVUs: 200,
    maxVUs: 2000,
    stages: [
      { duration: '30s', target: 500 }, // Initial spike
      { duration: '1m', target: 2000 }, // Peak viral traffic
      { duration: '30s', target: 100 }, // Cool down
    ],
    exec: 'viralSpikeTraffic',
    gracefulStop: '1m',
  },

  mixed: {
    executor: 'constant-vus',
    vus: 50,
    duration: '5m',
    exec: 'mixedWorkload',
    gracefulStop: '30s',
  },
};

// Get thresholds for selected scenario
const scenarioThresholds = getThresholdsForScenario(SCENARIO);

/**
 * Export K6 options
 */
export const options = {
  scenarios: {
    [SCENARIO]: scenarios[SCENARIO] || scenarios.quick,
  },
  thresholds: scenarioThresholds,
  noConnectionReuse: true,
  userAgent: 'K6-LoadTest/1.0 (MapleExpectation Issue #562)',
  setupTimeout: '2m',
  teardownTimeout: '1m',
};

// Apply custom duration if specified
if (DURATION > 0 && options.scenarios[SCENARIO]) {
  if (options.scenarios[SCENARIO].executor === 'constant-vus') {
    options.scenarios[SCENARIO].duration = `${DURATION}s`;
  }
}

// ============================================
// Scenario Executors
// ============================================

/**
 * Quick smoke test - validates basic functionality
 */
export function quickTest() {
  const testIGN = 'ZeroToHero';
  const url = `${config.baseUrl}${config.endpoints.expectation}/${testIGN}/expectation`;

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: 'quick_test' },
  });

  check(response, {
    'quick test status 200': (r) => r.status === 200,
    'quick test response time < 500ms': (r) => r.timings.duration < 500,
  });

  metrics.recordApiResponse('quick_test', response.body.length, response.timings.duration);
  metrics.recordLatencyBucket(response.timings.duration);
}

/**
 * Normal traffic pattern - realistic日常 load
 */
export function normalTraffic() {
  const testIGNs = ['ZeroToHero', 'NightLord', 'BishopMain', 'CorsairKing', 'AranLegacy'];
  const ign = testIGNs[Math.floor(Math.random() * testIGNs.length)];
  const url = `${config.baseUrl}${config.endpoints.expectation}/${ign}/expectation`;

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: 'normal_traffic' },
  });

  const cacheHit = response.headers['X-Cache'] === 'HIT';
  metrics.recordCacheResult(cacheHit, cacheHit ? response.timings.duration : 0);
  metrics.recordApiResponse('normal_traffic', response.body.length, response.timings.duration);
  metrics.recordLatencyBucket(response.timings.duration);

  check(response, {
    'normal traffic status 200': (r) => r.status === 200,
    'normal traffic response time < 300ms': (r) => r.timings.duration < 300,
  });

  // Simulate realistic user think time (1-3 seconds)
  const thinkTime = 1 + Math.random() * 2;
  __k2 ||= {};
  __k2.caffeinate = () => sleep(thinkTime);
  __k2.caffeinate();
}

/**
 * Patch day traffic - sustained high load
 */
export function patchDayTraffic() {
  const testIGNs = [
    'ZeroToHero', 'NightLord', 'BishopMain', 'CorsairKing', 'AranLegacy',
    'EvanMaster', 'MercedesPro', 'PhantomLord', 'LuminousStar', 'KaiserPride',
  ];
  const ign = testIGNs[Math.floor(Math.random() * testIGNs.length)];
  const url = `${config.baseUrl}${config.endpoints.expectation}/${ign}/expectation`;

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: 'patch_day_traffic' },
  });

  const cacheHit = response.headers['X-Cache'] === 'HIT';
  metrics.recordCacheResult(cacheHit, cacheHit ? response.timings.duration : 0);
  metrics.recordApiResponse('patch_day_traffic', response.body.length, response.timings.duration);
  metrics.recordLatencyBucket(response.timings.duration);

  check(response, {
    'patch day status 200': (r) => r.status === 200,
    'patch day response time < 200ms': (r) => r.timings.duration < 200,
  });
}

/**
 * Viral spike traffic - extreme burst load
 */
export function viralSpikeTraffic() {
  const testIGNs = [
    'ZeroToHero', 'NightLord', 'BishopMain', 'CorsairKing', 'AranLegacy',
    'EvanMaster', 'MercedesPro', 'PhantomLord', 'LuminousStar', 'KaiserPride',
    'AngelicaBuster', 'XenonSlayer', 'GohanFan', 'ViperStrike', 'BowMaster99',
  ];
  const ign = testIGNs[Math.floor(Math.random() * testIGNs.length)];
  const url = `${config.baseUrl}${config.endpoints.expectation}/${ign}/expectation`;

  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: 'viral_spike_traffic' },
  });

  metrics.recordApiResponse('viral_spike_traffic', response.body.length, response.timings.duration);
  metrics.recordLatencyBucket(response.timings.duration);

  check(response, {
    'viral spike status 200': (r) => r.status === 200,
    'viral spike response time < 500ms': (r) => r.timings.duration < 500,
  });
}

/**
 * Mixed workload - realistic traffic distribution
 * Uses the mixed-workload scenario module
 */
export function mixedWorkload() {
  // Dynamic import of mixed-workload scenario
  // In K6, we need to inline this since dynamic imports aren't fully supported
  const POPULAR_IGNS = [
    'ZeroToHero', 'NightLord', 'BishopMain', 'CorsairKing', 'AranLegacy',
    'EvanMaster', 'MercedesPro', 'PhantomLord', 'LuminousStar', 'KaiserPride',
  ];

  const RANDOM_IGNS = [
    'RandomUser_' + Math.random().toString(36).substring(7),
    'TestChar' + Math.floor(Math.random() * 10000),
  ];

  // Weighted distribution: 70% normal, 20% character info, 10% cache miss
  const requestType = Math.random();
  let ign, requestTag;

  if (requestType < 0.7) {
    // 70% expectation queries
    ign = POPULAR_IGNS[Math.floor(Math.random() * POPULAR_IGNS.length)];
    requestTag = 'expectation_query';
  } else if (requestType < 0.9) {
    // 20% character info
    ign = POPULAR_IGNS[Math.floor(Math.random() * POPULAR_IGNS.length)];
    requestTag = 'character_info';
  } else {
    // 10% cache miss
    ign = RANDOM_IGNS[Math.floor(Math.random() * RANDOM_IGNS.length)];
    requestTag = 'cache_miss_test';
  }

  const url = `${config.baseUrl}${config.endpoints.expectation}/${ign}/expectation`;
  const response = http.get(url, {
    headers: getCommonHeaders(),
    tags: { name: requestTag },
  });

  const cacheHit = response.headers['X-Cache'] === 'HIT';
  metrics.recordCacheResult(cacheHit, cacheHit ? response.timings.duration : 0);
  metrics.recordApiResponse(requestTag, response.body.length, response.timings.duration);
  metrics.recordLatencyBucket(response.timings.duration);

  check(response, {
    [`${requestTag} status 200`]: (r) => r.status === 200 || requestTag === 'cache_miss_test',
    [`${requestTag} response time < 300ms`]: (r) => r.timings.duration < 300,
  });

  // Realistic think time (1-3 seconds)
  const thinkTime = 1 + Math.random() * 2;
  __k2 ||= {};
  __k2.caffeinate = () => sleep(thinkTime);
  __k2.caffeinate();
}

// ============================================
// Setup & Teardown
// ============================================

/**
 * Setup function - runs once before test
 */
export function setup() {
  console.log(`=== K6 Load Test Starting ===`);
  console.log(`Scenario: ${SCENARIO}`);
  console.log(`Target: ${config.baseUrl}`);
  console.log(`Duration: ${DURATION > 0 ? DURATION + 's' : 'default'}`);

  // Health check before proceeding
  const healthy = waitForHealthy();
  if (!healthy) {
    console.error('Health check failed - aborting test');
    throw new Error('Application health check failed');
  }

  console.log('Health check passed - starting load test');
  return { startTime: Date.now() };
}

/**
 * Teardown function - runs once after test
 */
export function teardown(data) {
  const duration = ((Date.now() - data.startTime) / 1000).toFixed(1);
  console.log(`=== K6 Load Test Complete ===`);
  console.log(`Scenario: ${SCENARIO}`);
  console.log(`Duration: ${duration}s`);
  console.log(`Final metrics:`);
  console.log(`  Cache hits: ${metrics.cacheHits.name}`);
  console.log(`  Cache misses: ${metrics.cacheMisses.name}`);
  console.log(`  Total requests: ${metrics.apiRequestsByEndpoint.name}`);
}
