/**
 * K6 Load Test Thresholds
 * Issue #562: Load Testing + Optimization
 *
 * Centralized threshold definitions for different load test scenarios.
 * These thresholds define pass/fail criteria for each scenario.
 */

/**
 * Strict thresholds for acceptance testing
 * Acceptance criteria: p99 < 200ms at 500 QPS
 */
export const strictThresholds = {
  http_req_duration: ['p(99)<200', 'p(95)<100', 'p(90)<50'],
  http_req_failed: ['rate<0.01'], // < 1% errors
  http_reqs: ['count>1000'], // Minimum request count
  iterations: ['count>100'], // Minimum iterations
};

/**
 * Normal thresholds for baseline testing
 * Relaxed criteria for development/staging
 */
export const normalThresholds = {
  http_req_duration: ['p(99)<300', 'p(95)<150', 'p(90)<100'],
  http_req_failed: ['rate<0.005'], // < 0.5% errors
  http_reqs: ['count>100'],
  iterations: ['count>10'],
};

/**
 * Relaxed thresholds for stress testing
 * Allow higher latencies and error rates under extreme load
 */
export const relaxedThresholds = {
  http_req_duration: ['p(99)<500', 'p(95)<300', 'p(90)<200'],
  http_req_failed: ['rate<0.05'], // < 5% errors acceptable
  http_reqs: ['count>500'],
  iterations: ['count>50'],
};

/**
 * Viral spike thresholds
 * Most lenient - testing graceful degradation
 */
export const viralSpikeThresholds = {
  http_req_duration: ['p(99)<1000', 'p(95)<500', 'p(90)<300'],
  http_req_failed: ['rate<0.10'], // < 10% errors acceptable during spike
  http_reqs: ['count>100'],
  iterations: ['count>10'],
};

/**
 * Quick validation thresholds
 * For CI smoke tests and quick checks
 */
export const quickThresholds = {
  http_req_duration: ['p(99)<500'],
  http_req_failed: ['rate<0.05'],
  http_reqs: ['count>10'],
};

/**
 * Custom thresholds builder
 * @param {Object} options - Threshold options
 * @param {number} options.p99Latency - p99 latency threshold in ms
 * @param {number} options.errorRate - Maximum error rate (0-1)
 * @param {number} options.minRequests - Minimum request count
 * @returns {Object} Threshold configuration
 */
export function buildThresholds(options = {}) {
  const p99 = options.p99Latency || 200;
  const p95 = Math.floor(p99 * 0.7);
  const p90 = Math.floor(p99 * 0.5);
  const errorRate = options.errorRate || 0.01;
  const minRequests = options.minRequests || 100;

  return {
    http_req_duration: [`p(99)<${p99}`, `p(95)<${p95}`, `p(90)<${p90}`],
    http_req_failed: [`rate<${errorRate}`],
    http_reqs: [`count>${minRequests}`],
    iterations: [`count>${Math.floor(minRequests / 10)}`],
  };
}

/**
 * Get thresholds by scenario name
 * @param {string} scenario - Scenario name
 * @returns {Object} Threshold configuration
 */
export function getThresholdsForScenario(scenario) {
  switch (scenario) {
    case 'patch-day':
    case 'patchDay':
      return strictThresholds;
    case 'normal':
    case 'normal-traffic':
      return normalThresholds;
    case 'viral-spike':
    case 'viralSpike':
      return viralSpikeThresholds;
    case 'quick':
      return quickThresholds;
    case 'mixed':
    case 'mixed-workload':
      return normalThresholds;
    default:
      return normalThresholds;
  }
}

/**
 * Threshold violation messages for reporting
 */
export const thresholdMessages = {
  p99Latency: 'p99 latency exceeded threshold - user experience degraded',
  p95Latency: 'p95 latency exceeded threshold - most users affected',
  errorRate: 'Error rate exceeded threshold - reliability issue',
  minRequests: 'Minimum request count not met - test may be invalid',
  minIterations: 'Minimum iteration count not met - insufficient data',
};

/**
 * Validate thresholds against test results
 * @param {Object} summary - K6 test summary
 * @param {Object} thresholds - Threshold configuration
 * @returns {Object} Validation result with pass/fail and details
 */
export function validateThresholds(summary, thresholds) {
  const results = {
    passed: true,
    violations: [],
    metrics: {},
  };

  // Check HTTP request duration
  if (thresholds.http_req_duration) {
    const p99 = summary.metrics.http_req_duration?.values?.['p(99)'] || 0;
    const threshold = thresholds.http_req_duration[0].match(/\d+/)?.[0] || 200;

    results.metrics.p99Latency = {
      value: Math.round(p99),
      threshold: parseInt(threshold),
      passed: p99 < parseInt(threshold),
    };

    if (!results.metrics.p99Latency.passed) {
      results.passed = false;
      results.violations.push({
        metric: 'p99Latency',
        message: `p99 latency ${Math.round(p99)}ms exceeded threshold ${threshold}ms`,
      });
    }
  }

  // Check error rate
  if (thresholds.http_req_failed) {
    const failRate = summary.metrics.http_req_failed?.values?.rate || 0;
    const thresholdRate = thresholds.http_req_failed[0].match(/[\d.]+/)?.[0] || 0.01;

    results.metrics.errorRate = {
      value: (failRate * 100).toFixed(2) + '%',
      threshold: (parseFloat(thresholdRate) * 100).toFixed(2) + '%',
      passed: failRate < parseFloat(thresholdRate),
    };

    if (!results.metrics.errorRate.passed) {
      results.passed = false;
      results.violations.push({
        metric: 'errorRate',
        message: `Error rate ${(failRate * 100).toFixed(2)}% exceeded threshold ${(parseFloat(thresholdRate) * 100).toFixed(2)}%`,
      });
    }
  }

  return results;
}

export default {
  strictThresholds,
  normalThresholds,
  relaxedThresholds,
  viralSpikeThresholds,
  quickThresholds,
  buildThresholds,
  getThresholdsForScenario,
  thresholdMessages,
  validateThresholds,
};
