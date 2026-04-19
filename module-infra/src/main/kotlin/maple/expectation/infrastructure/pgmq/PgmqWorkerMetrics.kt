package maple.expectation.infrastructure.pgmq

import maple.expectation.infrastructure.pgmq.WorkerQueueMetrics

/**
 * PGMQ Worker 공통 메트릭
 *
 * @deprecated Replaced by [WorkerQueueMetrics] which provides comprehensive
 * queue/worker metrics with queue depth, in-flight, concurrent, success/failure/retry/dlq counters,
 * and wait duration timer. Use [WorkerQueueMetrics.forQueue] to get per-queue metrics.
 */
@Deprecated("Use WorkerQueueMetrics instead", ReplaceWith("WorkerQueueMetrics"))
@org.springframework.stereotype.Component
class PgmqWorkerMetrics
