/**
 * K6 Mixed Workload Scenario
 * Issue #562: Load Testing + Optimization
 *
 * Realistic traffic mix combining multiple request patterns:
 * - 70% expectation queries (normal traffic)
 * - 20% character info queries
 * - 10% cache miss scenarios (random IGNs)
 *
 * This simulates real-world usage where different request types
 * are interleaved rather than isolated.
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { config, getExpectationUrl } from '../lib/config.js';
import {
  weightedSelect,
  sleepWithJitter,
  getCommonHeaders,
  isCacheHit,
  getLatencyBucket,
} from '../lib/helpers.js';
import * as metrics from '../lib/metrics.js';

// Test data for realistic scenarios
const POPULAR_IGNS = [
  'ZeroToHero',
  'NightLord',
  'BishopMain',
  'CorsairKing',
  'AranLegacy',
  'EvanMaster',
  'MercedesPro',
  'PhantomLord',
  'LuminousStar',
  'KaiserPride',
  'AngelicaBuster',
  'XenonSlayer',
  'GohanFan',
  'ViperStrike',
  'BowMaster99',
  'ShadowerOps',
  'HeroOfTime',
  'PaladinHoly',
  'DarkKnightX',
  'ArchMageIce',
  'ArchMageFire',
  'BishopPure',
  'PathfinderPro',
  'BladeLord',
  'Buccaneer',
  'CannonShooter',
  'WildHunter',
  'MechanicEngineer',
  'DemonSlayer',
  'DemonAvenger',
  'AranWarrior',
  'EvanMagician',
  'Miraculous',
  'BattleMage',
  'WildHunterPro',
  'MechanicKing',
  'CygnusQueen',
  'DawnWarrior',
  'BlazeWizard',
  'WindArcher',
  'NightWalkerX',
  'ThunderBreaker',
  'AranReturns',
  'EvanReturns',
  'MercedesQueen',
  'PhantomThief',
  'LuminousHero',
  'KaiserEmperor',
  'AngelicBuster',
  'XenonMaster',
  'ZenUltimate',
  'HayatoBlade',
  'KannaShaman',
  'CadenaLink',
  'IlliumNovan',
  'ArkWarrior',
  'PathfinderQuest',
];

const RANDOM_IGNS = [
  'RandomUser_' + Math.random().toString(36).substring(7),
  'TestChar' + Math.floor(Math.random() * 10000),
  'CacheMiss' + Date.now(),
  'UnknownPlayer',
  'NoSuchCharacter',
];

/**
 * Request type definitions for weighted distribution
 */
const REQUEST_TYPES = [
  { type: 'expectation', weight: 0.7 }, // 70% expectation queries
  { type: 'character-info', weight: 0.2 }, // 20% character info
  { type: 'cache-miss', weight: 0.1 }, // 10% cache miss scenarios
];

/**
 * Perform expectation query (normal traffic pattern)
 * @returns {Object} Response data
 */
export function queryExpectation() {
  const ign = weightedSelect(
    POPULAR_IGNS.map(name => ({ item: name, weight: 1 }))
  );

  const startTime = Date.now();
  const response = http.get(getExpectationUrl(ign), {
    headers: getCommonHeaders(),
    tags: { name: 'expectation_query' },
  });
  const duration = Date.now() - startTime;

  // Record metrics
  const cacheHit = isCacheHit(response);
  metrics.recordCacheResult(cacheHit, cacheHit ? duration : 0);
  metrics.recordApiResponse('expectation_query', response.body.length, duration);
  metrics.recordLatencyBucket(duration);

  // Validate response
  check(response, {
    'expectation status 200': (r) => r.status === 200,
    'expectation has data': (r) => {
      try {
        const data = r.json();
        return data && typeof data === 'object';
      } catch {
        return false;
      }
    },
  });

  return { type: 'expectation', ign, cacheHit, duration };
}

/**
 * Query character information
 * @returns {Object} Response data
 */
export function queryCharacterInfo() {
  const ign = weightedSelect(
    POPULAR_IGNS.map(name => ({ item: name, weight: 1 }))
  );

  const startTime = Date.now();
  const response = http.get(getExpectationUrl(ign), {
    headers: {
      ...getCommonHeaders(),
      'X-Request-Type': 'character-info',
    },
    tags: { name: 'character_info' },
  });
  const duration = Date.now() - startTime;

  // Record metrics
  const cacheHit = isCacheHit(response);
  metrics.recordCacheResult(cacheHit, cacheHit ? duration : 0);
  metrics.recordApiResponse('character_info', response.body.length, duration);
  metrics.recordLatencyBucket(duration);

  check(response, {
    'character info status 200': (r) => r.status === 200,
  });

  return { type: 'character-info', ign, cacheHit, duration };
}

/**
 * Query with guaranteed cache miss (random IGN)
 * @returns {Object} Response data
 */
export function queryCacheMiss() {
  const ign = RANDOM_IGNS[Math.floor(Math.random() * RANDOM_IGNS.length)];

  const startTime = Date.now();
  const response = http.get(getExpectationUrl(ign), {
    headers: getCommonHeaders(),
    tags: { name: 'cache_miss_test' },
  });
  const duration = Date.now() - startTime;

  // Record metrics (always a miss for random IGNs)
  metrics.recordCacheResult(false, 0);
  metrics.recordApiResponse('cache_miss_test', response.body.length, duration);
  metrics.recordLatencyBucket(duration);

  // For cache miss tests, we expect either 404 or 200 with no data
  check(response, {
    'cache miss handled': (r) => r.status === 200 || r.status === 404,
  });

  return { type: 'cache-miss', ign, cacheHit: false, duration };
}

/**
 * Execute a mixed workload request based on weighted distribution
 * @returns {Object} Request result
 */
export function executeMixedRequest() {
  const requestType = weightedSelect(REQUEST_TYPES);

  let result;
  switch (requestType) {
    case 'expectation':
      result = queryExpectation();
      break;
    case 'character-info':
      result = queryCharacterInfo();
      break;
    case 'cache-miss':
      result = queryCacheMiss();
      break;
    default:
      result = queryExpectation();
  }

  // Add brief sleep between requests (1-3 seconds with jitter)
  sleepWithJitter(2000, 0.5);

  return result;
}

/**
 * Main scenario function for mixed workload
 * This is the entry point when running this scenario directly
 */
export default function () {
  executeMixedRequest();
}

/**
 * Setup function to initialize scenario
 */
export function setup() {
  console.log('=== Mixed Workload Scenario Starting ===');
  console.log('Traffic distribution:');
  console.log('  - Expectation queries: 70%');
  console.log('  - Character info: 20%');
  console.log('  - Cache miss tests: 10%');
  console.log(`Target: ${config.baseUrl}`);
}

/**
 * Teardown function for scenario cleanup
 */
export function teardown(data) {
  console.log('=== Mixed Workload Scenario Complete ===');
  console.log('Final metrics:');
  console.log(`  Cache hits: ${metrics.cacheHits.name}`);
  console.log(`  Cache misses: ${metrics.cacheMisses.name}`);
  console.log(`  Total requests: ${metrics.apiRequestsByEndpoint.name}`);
}
