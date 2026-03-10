package maple.expectation.infrastructure.batch.reader

import java.util.Iterator
import maple.expectation.core.domain.model.Page
import maple.expectation.core.domain.model.PageRequest
import maple.expectation.core.port.out.OcidQueryPort
import maple.expectation.infrastructure.executor.LogicExecutor
import maple.expectation.infrastructure.executor.TaskContext
import org.slf4j.LoggerFactory
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.annotation.BeforeStep
import org.springframework.batch.item.ItemReader
import org.springframework.stereotype.Component

/**
 * OCID Reader for Spring Batch (Issue #356)
 *
 * **기능**
 * - game_character 테이블에서 전체 OCID 조회
 * - JPA Cursor-based pagination (chunk size: 1000)
 * - Memory efficient: Iterator 패턴으로 상태 저장 최소화
 *
 * **CLAUDE.md 준수사항**
 * - Section 12: LogicExecutor.executeOrDefault (Zero Try-Catch)
 * - Section 15: 람다 3줄 초과 시 Private Method 추출
 * - Stateless: Iterator 사용, 상태 저장 최소화
 *
 * @see maple.expectation.core.port.out.OcidQueryPort
 */
@Component
class OcidReader(
    private val ocidQuery: OcidQueryPort,
    private val executor: LogicExecutor,
) : ItemReader<String> {

    // Chunk-based fetch for memory efficiency
    private var ocidIterator: Iterator<String>? = null
    private var currentPage: Int = 0
    private var hasNextPage: Boolean = true

    override fun read(): String? = executor.executeOrDefault(
        { readNextOcid() },
        null,
        TaskContext.of("OcidReader", "Read"),
    )

    /**
     * Read next OCID from iterator
     * Section 15: 람다 3줄 초과 시 Private Method 추출
     * ADR-084 P0 Fix: Iterator 소진을 먼저 체크하여 마지막 페이지의 OCID 누락 방지
     * @return next OCID or null if exhausted
     */
    private fun readNextOcid(): String? {
        if (ocidIterator == null || !ocidIterator!!.hasNext()) {
            fetchNextChunk()
        }

        // Iterator 소진을 먼저 체크하여 마지막 페이지의 OCID 반환
        if (ocidIterator != null && !ocidIterator!!.hasNext()) {
            return null
        }

        return ocidIterator?.next()
    }

    /**
     * Step 실행 전 상태 초기화 (Issue #356 P1, ADR-084)
     * @Component Singleton이므로 각 Batch Job 실행 전에 상태를 초기화해야 함.
     * 초기화하지 않으면 이전 실행의 currentPage가 남아서 데이터 누락 발생.
     * @param stepExecution 현재 Step 실행 컨텍스트
     */
    @BeforeStep
    fun initializeState(stepExecution: StepExecution) {
        this.ocidIterator = null
        this.currentPage = 0
        this.hasNextPage = true
        log.info("[OcidReader] State initialized for job: {}", stepExecution.jobExecutionId)
    }

    /**
     * Fetch next chunk of OCIDs using OcidQueryPort
     * Section 15: 람다 3줄 초과 시 Private Method 추출
     */
    private fun fetchNextChunk() {
        val pageRequest = PageRequest.of(currentPage, FETCH_SIZE)
        val page: Page<String> = ocidQuery.findAllOcids(pageRequest)

        hasNextPage = page.hasNext
        currentPage++

        ocidIterator = page.content.iterator() as java.util.Iterator<String>

        log.debug("[OcidReader] Fetched chunk: page={}, hasMore={}", currentPage, hasNextPage)
    }

    companion object {
        private val log = LoggerFactory.getLogger(OcidReader::class.java)
        private const val FETCH_SIZE = 1000
    }
}
