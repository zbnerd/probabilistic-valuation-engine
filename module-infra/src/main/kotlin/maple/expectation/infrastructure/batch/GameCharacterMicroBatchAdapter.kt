package maple.expectation.infrastructure.batch

import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import maple.expectation.core.domain.model.character.GameCharacter
import maple.expectation.domain.repository.GameCharacterRepository
import maple.expectation.infrastructure.config.AdaptiveMicroBatchProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import org.springframework.cache.Cache
import org.springframework.cache.concurrent.ConcurrentMapCache
import org.springframework.stereotype.Service

/**
 * GameCharacter Micro-Batch Adapter (Issue #588, #599)
 *
 * <h3>Purpose</h3>
 * <p>Connects GameCharacterRepository to AdaptiveMicroBatchUserService framework.
 *
 * <h3>Routing Logic</h3>
 * <ul>
 *   <li>Fast Lane: Single query when semaphore available</li>
 *   <li>Batch Lane: IN query when under high load</li>
 * </ul>
 *
 * @see AdaptiveMicroBatchUserService
 * @see GameCharacterRepository
 */
@Service
class GameCharacterMicroBatchAdapter(
    properties: AdaptiveMicroBatchProperties,
    logicExecutor: LogicExecutor,
    meterRegistry: MeterRegistry,
    private val repository: GameCharacterRepository,
) {
    private val cache: Cache = ConcurrentMapCache("gameCharacterCache")

    private val delegate = AdaptiveMicroBatchUserService<GameCharacter>(
        properties = properties,
        logicExecutor = logicExecutor,
        meterRegistry = meterRegistry,
        cache = cache,
        singleLoader = { key -> repository.findByUserIgn(key) },
        batchLoader = { keys -> repository.findByUserIgnIn(keys) },
    )

    @PostConstruct
    fun init() {
        delegate.startBatchWorker()
    }

    /**
     * Get character by user IGN with adaptive micro-batching
     *
     * @param userIgn in-game name
     * @return GameCharacter or null if not found
     */
    fun getByUserIgn(userIgn: String): GameCharacter? = delegate.getByKey(userIgn)
}
