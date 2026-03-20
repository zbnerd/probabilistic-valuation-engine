/**
 * K6 Load Test Helper Utilities
 * Issue #562: Load Testing + Optimization
 */

import { check, sleep } from 'k6';
import http from 'k6/http';
import { config, getHealthUrl } from './config.js';

/**
 * URL encode a string (handles Korean characters)
 * @param {string} str - String to encode
 * @returns {string} URL encoded string
 */
export function urlEncode(str) {
  return encodeURIComponent(str);
}

/**
 * Select a random item from an array
 * @param {Array} arr - Array to select from
 * @returns {*} Random item
 */
export function randomSelect(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/**
 * Select an item from array using weighted distribution
 * @param {Array<{item: *, weight: number}>} weightedItems - Items with weights
 * @returns {*} Selected item
 */
export function weightedSelect(weightedItems) {
  const totalWeight = weightedItems.reduce((sum, w) => sum + w.weight, 0);
  let random = Math.random() * totalWeight;

  for (const weighted of weightedItems) {
    random -= weighted.weight;
    if (random <= 0) {
      return weighted.item;
    }
  }

  return weightedItems[0].item;
}

/**
 * Generate a random sleep duration with jitter
 * @param {number} baseMs - Base sleep time in milliseconds
 * @param {number} jitterPercent - Jitter percentage (0-1)
 */
export function sleepWithJitter(baseMs, jitterPercent = 0.1) {
  const jitter = baseMs * jitterPercent * (Math.random() * 2 - 1);
  const sleepMs = baseMs + jitter;
  sleep(sleepMs / 1000);
}

/**
 * Common HTTP headers for all requests
 * @returns {Object} Headers object
 */
export function getCommonHeaders() {
  return {
    'Accept': 'application/json',
    'Accept-Encoding': 'gzip, deflate',
    'Content-Type': 'application/json',
  };
}

/**
 * Perform health check and wait for application to be ready
 * @param {number} maxRetries - Maximum number of retries
 * @param {number} intervalMs - Interval between retries in milliseconds
 * @returns {boolean} True if healthy, false otherwise
 */
export function waitForHealthy(maxRetries = null, intervalMs = null) {
  const retries = maxRetries || config.healthCheck.maxRetries;
  const interval = (intervalMs || config.healthCheck.intervalMs) / 1000;

  for (let i = 0; i < retries; i++) {
    const response = http.get(getHealthUrl(), {
      headers: getCommonHeaders(),
      timeout: '5s',
    });

    if (response.status === 200) {
      console.log(`Health check passed after ${i + 1} attempts`);
      return true;
    }

    console.log(`Health check attempt ${i + 1}/${retries} failed: ${response.status}`);
    sleep(interval);
  }

  console.error('Health check failed after maximum retries');
  return false;
}

/**
 * Check common response validations
 * @param {Object} response - HTTP response
 * @param {string} name - Check name prefix
 * @returns {boolean} True if all checks pass
 */
export function checkResponse(response, name = 'request') {
  return check(response, {
    [`${name} status is 200`]: (r) => r.status === 200,
    [`${name} has valid JSON`]: (r) => {
      try {
        r.json();
        return true;
      } catch (e) {
        return false;
      }
    },
    [`${name} response time < 500ms`]: (r) => r.timings.duration < 500,
  });
}

/**
 * Check for cache hit indicators in response
 * @param {Object} response - HTTP response
 * @returns {boolean} True if cache hit
 */
export function isCacheHit(response) {
  // Check X-Cache header (if available)
  const cacheHeader = response.headers['X-Cache'];
  if (cacheHeader) {
    return cacheHeader.toLowerCase() === 'hit';
  }

  // Fallback: check response time (cached responses are typically faster)
  return response.timings.duration < 50;
}

/**
 * Parse response time buckets for histogram
 * @param {number} durationMs - Response duration in milliseconds
 * @returns {string} Bucket label
 */
export function getLatencyBucket(durationMs) {
  if (durationMs < 50) return 'under_50ms';
  if (durationMs < 100) return '50_100ms';
  if (durationMs < 200) return '100_200ms';
  if (durationMs < 500) return '200_500ms';
  if (durationMs < 1000) return '500ms_1s';
  return 'over_1s';
}

/**
 * Format bytes to human readable string
 * @param {number} bytes - Number of bytes
 * @returns {string} Formatted string
 */
export function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
}

/**
 * Format duration to human readable string
 * @param {number} seconds - Duration in seconds
 * @returns {string} Formatted string
 */
export function formatDuration(seconds) {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);

  if (h > 0) {
    return `${h}h ${m}m ${s}s`;
  } else if (m > 0) {
    return `${m}m ${s}s`;
  }
  return `${s}s`;
}

export default {
  urlEncode,
  randomSelect,
  weightedSelect,
  sleepWithJitter,
  getCommonHeaders,
  waitForHealthy,
  checkResponse,
  isCacheHit,
  getLatencyBucket,
  formatBytes,
  formatDuration,
};
