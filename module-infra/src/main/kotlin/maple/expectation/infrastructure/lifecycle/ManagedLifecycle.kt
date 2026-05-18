package maple.expectation.infrastructure.lifecycle

/**
 * Centralized lifecycle contract for infrastructure resources that need explicit start/stop hooks.
 *
 * Components keep their own resource ownership, while ManagedLifecycleCoordinator owns ordering
 * and shutdown observability.
 */
interface ManagedLifecycle {
    val lifecycleName: String
        get() = this::class.java.simpleName

    val lifecyclePhase: Int
        get() = 0

    fun startLifecycle() {
        // default no-op
    }

    fun stopLifecycle()
}
