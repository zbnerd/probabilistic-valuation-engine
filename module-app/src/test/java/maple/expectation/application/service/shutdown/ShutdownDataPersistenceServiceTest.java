package maple.expectation.application.service.shutdown;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.shutdown.ShutdownProperties;
import maple.expectation.infrastructure.shutdown.dto.ShutdownData;
import maple.expectation.support.TestLogicExecutors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ShutdownDataPersistenceService 테스트
 *
 * <p>LogicExecutor의 모든 실행 패턴을 모킹하여 실제 로직이 수행되도록 보장합니다.
 *
 * <h4>Note: This is a pure unit test</h4>
 *
 * <p>This test does NOT use SharedContainers because:
 *
 * <ul>
 *   <li>It tests file I/O operations only (no DB/Redis needed)
 *   <li>Uses @TempDir for temporary file management
 *   <li>Mocks LogicExecutor for deterministic behavior
 * </ul>
 *
 * @since 2.0.0
 */
@DisplayName("ShutdownDataPersistenceService 테스트")
class ShutdownDataPersistenceServiceTest {

  @TempDir Path tempDir;
  private ShutdownDataPersistenceService service;
  private ObjectMapper objectMapper;
  private LogicExecutor executor;

  @BeforeEach
  void setUp() throws Throwable {
    // Jackson 설정
    objectMapper = new ObjectMapper();
    objectMapper.registerModule(new JavaTimeModule());
    objectMapper.findAndRegisterModules();

    // ✅ [해결] TestLogicExecutors로 80+ 줄 boilerplate 제거
    executor = TestLogicExecutors.passThrough();

    // 서비스 인스턴스 생성 (P1-1 Fix: ShutdownProperties 생성자 주입)
    ShutdownProperties shutdownProperties = new ShutdownProperties();
    shutdownProperties.setBackupDirectory(tempDir.toString());
    shutdownProperties.setArchiveDirectory(tempDir.resolve("processed").toString());
    shutdownProperties.setInstanceId("test-instance");
    service = new ShutdownDataPersistenceService(objectMapper, executor, shutdownProperties);

    service.init();
  }

  @Test
  @DisplayName("초기화 시 디렉토리 생성 테스트")
  void testInitCreatesDirectories() {
    // then
    assertThat(Files.exists(tempDir)).isTrue();
    assertThat(Files.exists(tempDir.resolve("processed"))).isTrue();
  }

  @Test
  @DisplayName("ShutdownData 저장 및 읽기 테스트")
  void testSaveAndReadShutdownData() {
    // given
    Map<String, Long> likeBuffer = Map.of("user1", 10L, "user2", 20L);
    List<String> equipmentPending = List.of("ocid1", "ocid2");

    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), "test-server", likeBuffer, equipmentPending);

    // when
    Path savedPath = service.saveShutdownData(data);

    // then
    assertThat(savedPath).isNotNull();
    assertThat(Files.exists(savedPath)).isTrue();

    // when - 파일 읽기
    Optional<ShutdownData> loaded = service.readBackupFile(savedPath);

    // then
    assertThat(loaded).isPresent();
    assertThat(loaded.get().getInstanceId()).isEqualTo("test-server");
    assertThat(loaded.get().getLikeBuffer()).hasSize(2);
  }

  @Test
  @DisplayName("빈 데이터 저장 시 null 반환 테스트")
  void testSaveEmptyDataReturnsNull() {
    // given
    ShutdownData emptyData = ShutdownData.empty("test-server");

    // when
    Path result = service.saveShutdownData(emptyData);

    // then
    assertThat(result).isNull();
  }

  @Test
  @DisplayName("appendLikeEntry - 개별 항목 추가 테스트")
  void testAppendLikeEntry() {
    // when
    service.appendLikeEntry("user1", 10L);
    service.appendLikeEntry("user2", 20L);

    // then
    List<Path> backupFiles = service.findAllBackupFiles();
    assertThat(backupFiles).isNotEmpty();

    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
    assertThat(loaded).isPresent();
    assertThat(loaded.get().getLikeBuffer()).containsEntry("user1", 10L);
    assertThat(loaded.get().getLikeBuffer()).containsEntry("user2", 20L);
  }

  @Test
  @DisplayName("appendLikeEntry - 동일 유저 중복 추가 시 합산 테스트")
  void testAppendLikeEntryMerge() {
    // when
    service.appendLikeEntry("user1", 10L);
    service.appendLikeEntry("user1", 5L);

    // then
    List<Path> backupFiles = service.findAllBackupFiles();
    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));

    assertThat(loaded).isPresent();
    assertThat(loaded.get().getLikeBuffer()).containsEntry("user1", 15L);
  }

  @Test
  @DisplayName("savePendingEquipment - Equipment 목록 저장 테스트")
  void testSavePendingEquipment() {
    // given
    List<String> ocids = List.of("ocid1", "ocid2", "ocid3");

    // when
    service.savePendingEquipment(ocids);

    // then
    List<Path> backupFiles = service.findAllBackupFiles();
    assertThat(backupFiles).hasSize(1);

    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
    assertThat(loaded).isPresent();
    assertThat(loaded.get().getEquipmentPending()).hasSize(3);
  }

  @Test
  @DisplayName("findAllBackupFiles - 백업 파일 스캔 테스트 (고정 파일명으로 원자적 교체)")
  void testFindAllBackupFiles() {
    // given
    ShutdownData data1 =
        new ShutdownData(LocalDateTime.now(), "server1", Map.of("u1", 1L), List.of());
    ShutdownData data2 =
        new ShutdownData(LocalDateTime.now(), "server2", Map.of("u2", 2L), List.of());

    // when
    // CLAUDE.md Section 24: Thread.sleep() 제거 - 동기 저장이므로 지연 불필요
    // 두 번째 저장이 첫 번째를 원자적으로 교체하는 동작은 시간과 무관
    service.saveShutdownData(data1);
    service.saveShutdownData(data2);

    // then - P1 Fix: 고정 파일명 사용으로 인스턴스당 1개 파일만 유지
    // 두 번째 저장이 첫 번째를 원자적으로 교체함
    List<Path> backupFiles = service.findAllBackupFiles();
    assertThat(backupFiles).hasSize(1);
    assertThat(backupFiles).allMatch(path -> path.toString().endsWith(".json"));

    // 최신 데이터(data2)가 저장되어 있어야 함
    Optional<ShutdownData> loaded = service.readBackupFile(backupFiles.get(0));
    assertThat(loaded).isPresent();
    assertThat(loaded.get().getLikeBuffer()).containsEntry("u2", 2L);
  }

  @Test
  @DisplayName("archiveFile - 파일 아카이브 테스트")
  void testArchiveFile() {
    // given
    ShutdownData data =
        new ShutdownData(LocalDateTime.now(), "test-server", Map.of("u1", 1L), List.of());
    Path savedPath = service.saveShutdownData(data);
    assertThat(Files.exists(savedPath)).isTrue();

    // when
    service.archiveFile(savedPath);

    // then
    assertThat(Files.exists(savedPath)).isFalse();
    Path archivedPath = tempDir.resolve("processed").resolve(savedPath.getFileName());
    assertThat(Files.exists(archivedPath)).isTrue();
  }

  @Test
  @DisplayName("JSON 직렬화/역직렬화 정확도 테스트")
  void testJsonSerializationAccuracy() {
    // given
    LocalDateTime now = LocalDateTime.now();
    ShutdownData original = new ShutdownData(now, "test-server", Map.of("u1", 1L), List.of("o1"));

    // when
    Path savedPath = service.saveShutdownData(original);
    Optional<ShutdownData> loaded = service.readBackupFile(savedPath);

    // then
    assertThat(loaded).isPresent();
    ShutdownData restored = loaded.get();
    assertThat(restored.getInstanceId()).isEqualTo(original.getInstanceId());
    assertThat(restored.getTimestamp()).isEqualToIgnoringNanos(original.getTimestamp());
  }

  @Test
  @DisplayName("백업 파일이 없을 때 빈 리스트 반환 테스트")
  void testFindAllBackupFilesWhenEmpty() {
    // when
    List<Path> backupFiles = service.findAllBackupFiles();

    // then
    assertThat(backupFiles).isEmpty();
  }

  @Test
  @DisplayName("손상된 JSON 파일 읽기 시 Optional.empty 반환 테스트")
  void testReadCorruptedFile() throws Exception {
    // given
    Path corruptedFile = tempDir.resolve("corrupted.json");
    Files.writeString(corruptedFile, "{ invalid json content }");

    // when
    // 🚀 이제 readBackupFile 내부에서 executeWithRecovery를 사용하여
    // 예외를 잡고 Optional.empty()를 반환하므로 테스트가 성공합니다.
    Optional<ShutdownData> result = service.readBackupFile(corruptedFile);

    // then
    assertThat(result).isEmpty();
  }
}
