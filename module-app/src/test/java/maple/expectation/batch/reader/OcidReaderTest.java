package maple.expectation.batch.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import maple.expectation.common.function.ThrowingSupplier;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import maple.expectation.infrastructure.persistence.entity.GameCharacterJpaEntity;
import maple.expectation.infrastructure.persistence.jpa.GameCharacterJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.StepExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * OcidReader 단위 테스트
 *
 * <p>ADR-084: P0 데이터 누락 수정, P1 상태 초기화 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OcidReader 단위 테스트")
class OcidReaderTest {

  private static final int FETCH_SIZE = 1000;

  @Mock private GameCharacterJpaRepository repository;
  @Mock private LogicExecutor executor;
  @Mock private StepExecution stepExecution;

  private OcidReader reader;

  @BeforeEach
  void setUp() {
    reader = new OcidReader(repository, executor);
    lenient().when(stepExecution.getJobExecutionId()).thenReturn(1L);
  }

  @Nested
  @DisplayName("ADR-084 P0: 마지막 페이지 OCID 데이터 누락 방지")
  class P0DataLossPreventionTests {

    @Test
    @DisplayName("마지막 페이지 OCID가 모두 반환되어야 함 (2500개: 1000, 1000, 500)")
    void lastPageOcids_ShouldAllBeReturned() {
      // Given: 총 2500개 OCID (3페이지: 1000, 1000, 500)
      when(repository.findAll(any(PageRequest.class)))
          .thenReturn(createPage(0, 1000, true))
          .thenReturn(createPage(1, 1000, true))
          .thenReturn(createPage(2, 500, false))
          .thenReturn(new PageImpl<>(List.of())); // Empty page after all data consumed

      when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                ThrowingSupplier<String> task = invocation.getArgument(0);
                Object defaultValue = invocation.getArgument(1);
                try {
                  return task.get();
                } catch (Throwable e) {
                  return defaultValue;
                }
              });

      // When: 2501번 read() 호출 (마지막 null 반환 포함)
      List<String> ocids = new ArrayList<>();
      for (int i = 0; i < 2501; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        ocids.add(ocid);
      }

      // Then: 2500개 OCID 모두 반환됨 (마지막 500개 포함)
      assertThat(ocids).hasSize(2500);
      // Page 0, 1, 2 fetched + 1 empty page after exhaustion = 4 calls total
      verify(repository, times(4)).findAll(any(PageRequest.class));
      verify(repository).findAll(PageRequest.of(0, FETCH_SIZE));
      verify(repository).findAll(PageRequest.of(1, FETCH_SIZE));
      verify(repository).findAll(PageRequest.of(2, FETCH_SIZE));
      verify(repository).findAll(PageRequest.of(3, FETCH_SIZE));
    }

    @Test
    @DisplayName("정확히 한 페이지 분량의 데이터가 있을 때 모두 반환되어야 함")
    void exactOnePage_ShouldReturnAllOcids() {
      // Given: 정확히 1000개 OCID (1페이지)
      when(repository.findAll(any(PageRequest.class)))
          .thenReturn(createPage(0, 1000, false))
          .thenReturn(new PageImpl<>(List.of())); // Empty page after all data consumed

      when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                ThrowingSupplier<String> task = invocation.getArgument(0);
                Object defaultValue = invocation.getArgument(1);
                try {
                  return task.get();
                } catch (Throwable e) {
                  return defaultValue;
                }
              });

      // When: 1001번 read() 호출
      List<String> ocids = new ArrayList<>();
      for (int i = 0; i < 1001; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        ocids.add(ocid);
      }

      // Then: 1000개 OCID 모두 반환됨
      assertThat(ocids).hasSize(1000);
      // Page 0 fetched + 1 empty page after exhaustion = 2 calls total
      verify(repository, times(2)).findAll(any(PageRequest.class));
      verify(repository).findAll(PageRequest.of(0, FETCH_SIZE));
      verify(repository).findAll(PageRequest.of(1, FETCH_SIZE));
    }

    @Test
    @DisplayName("데이터가 없을 때 null을 반환해야 함")
    void noData_ShouldReturnNull() {
      // Given: 빈 페이지
      when(repository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of()));

      when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                ThrowingSupplier<String> task = invocation.getArgument(0);
                Object defaultValue = invocation.getArgument(1);
                try {
                  return task.get();
                } catch (Throwable e) {
                  return defaultValue;
                }
              });

      // When: read() 호출
      String ocid = reader.read();

      // Then: null 반환
      assertThat(ocid).isNull();
    }
  }

  @Nested
  @DisplayName("ADR-084 P1: Step 재실행 시 상태 초기화")
  class P1StateInitializationTests {

    @Test
    @DisplayName("@BeforeStep에서 상태가 초기화되어야 함")
    void beforeStep_ShouldInitializeState() {
      // Given: 이전 실행으로 상태가 변경됨
      when(repository.findAll(any(PageRequest.class)))
          .thenReturn(createPage(0, 1000, false))
          .thenReturn(new PageImpl<>(List.of())); // Empty page after all data consumed

      when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                ThrowingSupplier<String> task = invocation.getArgument(0);
                Object defaultValue = invocation.getArgument(1);
                try {
                  return task.get();
                } catch (Throwable e) {
                  return defaultValue;
                }
              });

      // 첫 번째 실행: 100개만 읽고 중단 (상태 변경)
      List<String> firstRun = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        firstRun.add(ocid);
      }

      // When: @BeforeStep 호출 (새로운 mock 설정 필요)
      // 상태 초기화 후 다시 읽을 때 새로운 데이터 반환하도록 mock 재설정
      when(repository.findAll(any(PageRequest.class)))
          .thenReturn(createPage(0, 1000, false))
          .thenReturn(new PageImpl<>(List.of())) // 첫 실행의 두 번째 호출
          .thenReturn(createPage(0, 100, false)); // 초기화 후 다시 읽을 때

      reader.initializeState(stepExecution);

      // Then: 두 번째 실행에서도 처음부터 데이터 읽기
      String secondRunFirstOcid = reader.read();
      assertThat(secondRunFirstOcid).isEqualTo("ocid-0");
    }

    @Test
    @DisplayName("초기화 없이 재실행하면 데이터 누락 발생")
    void reexecuteWithoutInitialization_ShouldMissData() {
      // Given: 2500개 OCID (3페이지)
      when(repository.findAll(any(PageRequest.class)))
          .thenReturn(createPage(0, 1000, true))
          .thenReturn(createPage(1, 1000, true))
          .thenReturn(createPage(2, 500, false))
          .thenReturn(new PageImpl<>(List.of())); // Empty page after all data consumed

      when(executor.executeOrDefault(any(), any(), any(TaskContext.class)))
          .thenAnswer(
              invocation -> {
                ThrowingSupplier<String> task = invocation.getArgument(0);
                Object defaultValue = invocation.getArgument(1);
                try {
                  return task.get();
                } catch (Throwable e) {
                  return defaultValue;
                }
              });

      // 첫 번째 실행: 모든 데이터 읽기
      List<String> firstRun = new ArrayList<>();
      for (int i = 0; i < 2501; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        firstRun.add(ocid);
      }

      // When: 초기화 없이 재실행
      List<String> secondRun = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        String ocid = reader.read();
        if (ocid == null) break;
        secondRun.add(ocid);
      }

      // Then: 두 번째 실행에서 데이터가 누락됨 (currentPage가 3인 상태)
      assertThat(secondRun).isEmpty();
    }
  }

  // ==================== Helper Methods ====================

  private Page<GameCharacterJpaEntity> createPage(int pageNumber, int size, boolean hasNext) {
    List<GameCharacterJpaEntity> entities = new ArrayList<>();
    long startOcid = (long) pageNumber * FETCH_SIZE;

    for (long i = 0; i < size; i++) {
      String ocid = "ocid-" + (startOcid + i);
      GameCharacterJpaEntity entity =
          new GameCharacterJpaEntity(
              maple.expectation.domain.model.character.UserIgn.of("testUser"),
              maple.expectation.domain.model.character.CharacterId.of(ocid));
      entities.add(entity);
    }

    return new PageImpl<>(
        entities,
        PageRequest.of(pageNumber, FETCH_SIZE, Sort.unsorted()),
        hasNext ? (pageNumber + 1) * FETCH_SIZE + 1 : (long) pageNumber * FETCH_SIZE + size);
  }
}
