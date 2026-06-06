package maple.expectation.infrastructure.concurrency

enum class ShutdownPhase {
    CONSUMERS,
    PRODUCERS,
    INFRA
}
