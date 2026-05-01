package maple.expectation.infrastructure.aop.aspect

import java.util.Optional
import maple.expectation.common.function.ThrowingSupplier
import maple.expectation.infrastructure.cache.port.EquipmentCache
import maple.expectation.infrastructure.config.NexonApiProperties
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import maple.expectation.infrastructure.external.dto.v2.EquipmentResponse
import maple.expectation.infrastructure.lock.LeaderElectionStrategy
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class NexonDataCacheAspectTest {

    @Test
    fun `disabled equipment single flight bypasses leader election`() {
        val cache = mock<EquipmentCache>()
        val leaderElection = mock<LeaderElectionStrategy>()
        val executor = mock<LogicExecutor>()
        val properties = NexonApiProperties().apply {
            equipmentCacheSingleFlightEnabled = false
        }
        val aspect = NexonDataCacheAspect(cache, leaderElection, executor, properties)
        val joinPoint = mock<ProceedingJoinPoint>()
        val signature = mock<MethodSignature>()
        val response = EquipmentResponse(characterClass = "hero")

        whenever(cache.getValidCache("ocid-1")).thenReturn(Optional.empty())
        whenever(cache.hasNegativeCache("ocid-1")).thenReturn(false)
        whenever(joinPoint.signature).thenReturn(signature)
        whenever(signature.returnType).thenReturn(EquipmentResponse::class.java)
        whenever(joinPoint.proceed()).thenReturn(response)
        whenever(executor.execute(any<ThrowingSupplier<Any>>(), any<TaskContext>())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            (invocation.arguments[0] as ThrowingSupplier<Any>).get()
        }

        aspect.handleNexonCache(joinPoint, "ocid-1")

        verify(leaderElection, never()).executeWithLeaderElection<Any>(
            any<String>(),
            any<Int>(),
            any<ThrowingSupplier<Any>>(),
            any<ThrowingSupplier<Any>>(),
        )
    }
}
