package maple.expectation.infrastructure.concurrency

enum class ExecutorQualifier {
    CALCULATION,
    IO,
    SCHEDULER,
    CHUNK,
    BACKFILL,
}
