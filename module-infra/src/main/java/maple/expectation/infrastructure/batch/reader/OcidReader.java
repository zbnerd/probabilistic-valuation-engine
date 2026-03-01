package maple.expectation.infrastructure.batch.reader;

import java.util.Iterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.core.domain.model.Page;
import maple.expectation.core.domain.model.PageRequest;
import maple.expectation.core.port.out.OcidQueryPort;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

/**
 * OCID Reader for Spring Batch (Issue #356)
 *
 * <h3>기능</h3>
 *
 * <ul>
 *   <li>game_character 테이블에서 전체 OCID 조회
 *   <li>JPA Cursor-based pagination (chunk size: 1000)
 *   <li>Memory efficient: Iterator 패턴으로 상태 저장 최소화
 * </ul>
 *
 * <h4>CLAUDE.md 준수사항</h4>
 *
 * <ul>
 *   <li>Section 12: LogicExecutor.executeOrDefault (Zero Try-Catch)
 *   <li>Section 15: 람다 3줄 초과 시 Private Method 추출
 *   <li>Stateless: Iterator 사용, 상태 저장 최소화
 * </ul>
 *
 * @see maple.expectation.core.port.out.OcidQueryPort
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcidReader implements ItemReader<String> {

  private final OcidQueryPort ocidQuery;
  private final LogicExecutor executor;

  // Chunk-based fetch for memory efficiency
  private static final int FETCH_SIZE = 1000;
  private Iterator<String> ocidIterator;
  private int currentPage = 0;
  private boolean hasNextPage = true;

  @Override
  public String read() {
    return executor.executeOrDefault(
        this::readNextOcid, null, TaskContext.of("OcidReader", "Read"));
  }

  /**
   * Read next OCID from iterator
   *
   * <p>Section 15: 람다 3줄 초과 시 Private Method 추출
   *
   * <p>ADR-084 P0 Fix: Iterator 소진을 먼저 체크하여 마지막 페이지의 OCID 누락 방지
   *
   * @return next OCID or null if exhausted
   */
  private String readNextOcid() {
    if (ocidIterator == null || !ocidIterator.hasNext()) {
      fetchNextChunk();
    }

    // Iterator 소진을 먼저 체크하여 마지막 페이지의 OCID 반환
    if (ocidIterator != null && !ocidIterator.hasNext()) {
      return null;
    }

    return ocidIterator.next();
  }

  /**
   * Step 실행 전 상태 초기화 (Issue #356 P1, ADR-084)
   *
   * <p>@Component Singleton이므로 각 Batch Job 실행 전에 상태를 초기화해야 함. 초기화하지 않으면 이전 실행의 currentPage가 남아서 데이터
   * 누락 발생.
   *
   * @param stepExecution 현재 Step 실행 컨텍스트
   */
  @BeforeStep
  public void initializeState(StepExecution stepExecution) {
    this.ocidIterator = null;
    this.currentPage = 0;
    this.hasNextPage = true;
    log.info("[OcidReader] State initialized for job: {}", stepExecution.getJobExecutionId());
  }

  /**
   * Fetch next chunk of OCIDs using OcidQueryPort
   *
   * <p>Section 15: 람다 3줄 초과 시 Private Method 추출
   */
  private void fetchNextChunk() {
    PageRequest pageRequest = PageRequest.Companion.of(currentPage, FETCH_SIZE);
    Page<String> page = ocidQuery.findAllOcids(pageRequest);

    hasNextPage = page.getHasNext();
    currentPage++;

    ocidIterator = page.getContent().iterator();

    log.debug("[OcidReader] Fetched chunk: page={}, hasMore={}", currentPage, hasNextPage);
  }
}
