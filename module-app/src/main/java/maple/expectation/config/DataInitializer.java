package maple.expectation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.infrastructure.persistence.repository.MemberRepository;
import maple.expectation.domain.v2.Member;
import maple.expectation.infrastructure.executor.LogicExecutor;
import maple.expectation.infrastructure.executor.TaskContext;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** 시스템 초기 데이터 초기화 (LogicExecutor 평탄화 적용) */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

  private final MemberRepository memberRepository;
  private final LogicExecutor executor; // ✅ LogicExecutor 주입

  private static final String DEVELOPER_UUID = "00000000-0000-0000-0000-000000000000";

  @Override
  public void run(String... args) {
    // [패턴 1] 단순 실행 (체크 예외 노이즈 제거)
    executor.executeVoidJava(this::initDeveloper, TaskContext.of("System", "BootInit"));
  }

  /** 개발자 계정 초기화 로직 (평탄화 완료) */
  private void initDeveloper() {
    // 1. 존재 여부 확인 (Pre-condition)
    if (memberRepository.findByUuid(DEVELOPER_UUID) != null) {
      return;
    }

    // 2. TaskContext 생성 (카디널리티 통제 정책 준수)
    TaskContext context = TaskContext.of("System", "InitDeveloper", DEVELOPER_UUID);

    // 3. [패턴 3] 실패 시 경고 로그만 남기고 무시 (executeOrDefault와 유사한 효과)
    // 여기서는 예외 발생 시 전역 핸들러로 던지지 않고 조용히 넘어가기 위해 Recovery 패턴 활용
    executor.executeOrCatch(
        () -> this.createAndSaveDeveloper(context), this::handleInitConflict, context);
  }

  /** 계정 생성 및 저장 (핵심 로직 분리) */
  private Object createAndSaveDeveloper(TaskContext context) {
    log.info("🚀 시스템 초기 데이터 생성: 개발자 계정 ({})", context.getDynamicValue());

    Member developer = Member.Companion.createSystemAdmin(DEVELOPER_UUID, 0L);
    memberRepository.save(developer);

    log.info("✅ 개발자 계정 생성 완료");
    return null;
  }

  /** 초기화 충돌 처리 (평탄화: catch 블록 대체) */
  private Object handleInitConflict(Throwable e) {
    log.warn("⏭️ 초기 데이터가 이미 존재하거나 생성 중 충돌이 발생했습니다. (Reason: {})", e.getMessage());
    return null;
  }
}
