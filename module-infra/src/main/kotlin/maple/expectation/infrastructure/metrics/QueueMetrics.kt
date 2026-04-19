package maple.expectation.infrastructure.metrics

import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics

/**
 * Queue Depth Metrics (ADR-355)
 *
 * @deprecated Replaced by [WorkerQueueMetrics] which provides comprehensive
 * queue/worker metrics including queue depth, in-flight, concurrent, and wait duration.
 * Queue depth is now updated per poll cycle in PgmqWorker instead of on each Prometheus scrape,
 * reducing database load.
 */
@Deprecated("Use WorkerQueueMetrics instead", ReplaceWith("WorkerQueueMetrics"))
@org.springframework.stereotype.Component
class QueueMetrics
