package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier

/**
 * Leader Election Strategy Interface
 *
 * <p>Abstracts the leader election mechanism used for character synchronization.
 * Implementations can use Redis RCountDownLatch or PostgreSQL Advisory Locks.
 */
interface LeaderElectionStrategy {

    /**
     * Execute task with leader election
     *
     * <p>The first caller to acquire the lock becomes the leader and executes the leader task.
     * Subsequent callers become followers and wait for the leader to complete.
     *
     * @param key Unique identifier for the election (e.g., character name)
     * @param waitTimeSeconds Maximum time followers will wait for the leader
     * @param leaderTask Task executed by the leader (e.g., create new character)
     * @param followerTask Task executed by followers (e.g., wait and read from DB)
     * @return Result of the executed task
     */
    fun <T> executeWithLeaderElection(
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
    ): T
}
