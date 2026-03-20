/**
 * K6 Load Test Configuration
 * Issue #562: Load Testing + Optimization
 *
 * Centralized configuration for all load test scenarios.
 * Environment variables take precedence over defaults.
 */

export const config = {
  // Base URL for the application under test
  baseUrl: __ENV.BASE_URL || 'http://localhost:8080',

  // API endpoints
  endpoints: {
    health: '/actuator/health',
    expectation: '/api/v4/characters',
  },

  // Default thresholds for load tests
  thresholds: {
    // Strict thresholds for acceptance testing (patch-day scenario)
    strict: {
      httpReqDuration: ['p(99)<200'], // p99 < 200ms (acceptance criteria)
      httpReqFailed: ['rate<0.01'],   // < 1% error rate
    },
    // Relaxed thresholds for stress testing
    relaxed: {
      httpReqDuration: ['p(99)<500'], // p99 < 500ms
      httpReqFailed: ['rate<0.05'],   // < 5% error rate
    },
    // Baseline thresholds for normal traffic
    baseline: {
      httpReqDuration: ['p(99)<300'], // p99 < 300ms
      httpReqFailed: ['rate<0.001'],  // < 0.1% error rate
    },
  },

  // Scenario durations (in seconds, can be overridden via env)
  durations: {
    warmup: parseInt(__ENV.WARMUP_DURATION || '120'),    // 2 minutes default
    sustained: parseInt(__ENV.SUSTAINED_DURATION || '600'), // 10 minutes default
    quick: parseInt(__ENV.QUICK_DURATION || '30'),       // 30 seconds for quick tests
    cooldown: parseInt(__ENV.COOLDOWN_DURATION || '30'), // 30 seconds
  },

  // Virtual User counts
  vus: {
    min: parseInt(__ENV.MIN_VUS || '10'),
    normal: parseInt(__ENV.NORMAL_VUS || '50'),
    high: parseInt(__ENV.HIGH_VUS || '500'),
    stress: parseInt(__ENV.STRESS_VUS || '2000'),
  },

  // Request rates (requests per second)
  rps: {
    normal: parseFloat(__ENV.NORMAL_RPS || '0.5'),
    patchDay: parseFloat(__ENV.PATCH_DAY_RPS || '500'),
    viralSpike: parseFloat(__ENV.VIRAL_SPIKE_RPS || '2000'),
  },

  // Timeouts (in milliseconds)
  timeouts: {
    connect: parseInt(__ENV.CONNECT_TIMEOUT || '5000'),
    request: parseInt(__ENV.REQUEST_TIMEOUT || '30000'),
    response: parseInt(__ENV.RESPONSE_TIMEOUT || '30000'),
  },

  // Health check configuration
  healthCheck: {
    maxRetries: parseInt(__ENV.HEALTH_RETRIES || '30'),
    intervalMs: parseInt(__ENV.HEALTH_INTERVAL || '1000'),
  },
};

/**
 * Get the full URL for an endpoint
 * @param {string} path - The API path
 * @returns {string} Full URL
 */
export function getUrl(path) {
  return `${config.baseUrl}${path}`;
}

/**
 * Get the expectation API URL for a character
 * @param {string} ign - Character IGN (URL encoded)
 * @returns {string} Full URL
 */
export function getExpectationUrl(ign) {
  return getUrl(`${config.endpoints.expectation}/${ign}/expectation`);
}

/**
 * Get health check URL
 * @returns {string} Health check URL
 */
export function getHealthUrl() {
  return getUrl(config.endpoints.health);
}

/**
 * Build scenario options with common settings
 * @param {Object} options - Scenario-specific options
 * @returns {Object} Complete options object
 */
export function buildOptions(options) {
  return {
    thresholds: options.thresholds || config.thresholds.strict,
    noConnectionReuse: options.noConnectionReuse !== false,
    userAgent: 'K6-LoadTest/1.0 (MapleExpectation Issue #562)',
    ...options,
  };
}

export default config;
