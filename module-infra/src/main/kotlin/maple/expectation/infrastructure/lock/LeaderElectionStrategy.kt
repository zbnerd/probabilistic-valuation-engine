package maple.expectation.infrastructure.lock

import maple.expectation.common.function.ThrowingSupplier
import java.util.concurrent.CompletableFuture

/**
 * Leader Election Strategy Interface
 *
 * <p>Abstracts the leader election mechanism used for character synchronization.
 * Implementations can use Redis RCountDownLatch or PostgreSQL Advisory Locks.
 */
interface LeaderElectionStrategy {

    /**
     * Async: Execute task with leader election.
     *
     * <p>The first caller to acquire the lock becomes the leader and executes the leader task.
     * Subsequent callers become followers and wait for the leader to complete.
     *
     * @param key Unique identifier for the election (e.g., character name)
     * @param waitTimeSeconds Maximum time followers will wait for the leader
     * @param leaderSupplier Async task executed by the leader (e.g., create new character)
     * @param followerSupplier Async task executed by followers (e.g., wait and read from DB)
     * @return CompletableFuture that completes with the result of the leader or follower task
     */
    fun <T> executeWithLeaderElectionAsync(
        key: String,
        waitTimeSeconds: Int,
        leaderSupplier: () -> CompletableFuture<T>,
        followerSupplier: () -> CompletableFuture<T>,
    ): CompletableFuture<T>

    /**
     * Legacy sync: Execute task with leader election.
     *
     * <p>The first caller to acquire the lock becomes the leader and executes the leader task.
     * Subsequent callers become followers and wait for the leader to complete.
     *
     * @param key Unique identifier for the election (e.g., character name)
     * @param waitTimeSeconds Maximum time followers will wait for the leader
     * @param leaderTask Task executed by the leader (e.g., create new character)
     * @param followerTask Task executed by followers (e.g., wait and read from DB)
     *
     * @deprecated Use executeWithLeaderElectionAsync. Soft-deprecated for module-app legacy.
     *             module-app migration = follow-up PR.
     *
     * @see executeWithLeaderElectionAsync
     */
    @Deprecated("Use executeWithLeaderElectionAsync", ReplaceWith("executeWithLeaderElectionAsync(key, waitTimeSeconds, { CompletableFuture.completedFuture(leaderTask.get()) }, { CompletableFuture.completedFuture(followerTask.get()) })"))
    fun <T> executeWithLeaderElection(
        key: String,
        waitTimeSeconds: Int,
        leaderTask: ThrowingSupplier<T>,
        followerTask: ThrowingSupplier<T>,
    ): T
}