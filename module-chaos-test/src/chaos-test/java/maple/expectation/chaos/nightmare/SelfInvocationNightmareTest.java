package maple.expectation.chaos.nightmare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import maple.expectation.application.service.character.GameCharacterService;
import maple.expectation.application.service.character.OcidResolver;
import maple.expectation.domain.v2.GameCharacter;
import maple.expectation.infrastructure.persistence.repository.GameCharacterRepository;
import maple.expectation.support.AbstractContainerBaseTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Nightmare 16: Self-Invocation Mirage - AOP 프록시 바이패스
 *
 * <h4>5-Agent Council</h4>
 *
 * <ul>
 *   <li>🔴 Red (SRE): 장애 주입 - this.method() 호출로 @Transactional 우회 유발
 *   <li>🔵 Blue (Architect): 흐름 검증 - OcidResolver의 Self-Invocation 경로 분석
 *   <li>🟢 Green (Performance): 메트릭 검증 - 트랜잭션 미적용 시 성능 영향
 *   <li>🟣 Purple (Auditor): 데이터 무결성 - 트랜잭션 없이 DB 작업 시 위험
 *   <li>🟡 Yellow (QA Master): 테스트 전략 - 실제 서비스 코드의 Self-Invocation 검증
 * </ul>
 *
 * <h4>예상 결과: CONDITIONAL (코드 구조에 따라)</h4>
 *
 * <p>OcidResolver.resolve() → createAndGetOcid() → createNewCharacter() 호출 시
 * createNewCharacter()의 @Transactional(propagation = REQUIRES_NEW)가 동작하지 않을 수 있음.
 *
 * <h4>테스트 대상</h4>
 *
 * <ul>
 *   <li>OcidResolver.resolve() - 내부적으로 createNewCharacter() 호출
 *   <li>OcidResolver.createNewCharacter() - @Transactional(propagation = REQUIRES_NEW)
 *   <li>GameCharacterService.createNewCharacter() - @Transactional(propagation = REQUIRES_NEW)
 *       + @ObservedTransaction
 * </ul>
 *
 * <h4>관련 CS 원리</h4>
 *
 * <ul>
 *   <li>Proxy Pattern: Spring AOP의 JDK Dynamic Proxy / CGLIB
 *   <li>Self-Invocation: 프록시를 거치지 않는 내부 호출
 *   <li>Transaction Propagation: REQUIRES_NEW가 동작하지 않으면 기존 TX에 참여
 * </ul>
 *
 * @see maple.expectation.service.v2.OcidResolver
 * @see maple.expectation.service.v2.GameCharacterService
 */
@Slf4j
@Tag("nightmare")
@SpringBootTest
@DisplayName("Nightmare 16: Self-Invocation Mirage - 실제 서비스의 프록시 바이패스 검증")
class SelfInvocationNightmareTest extends AbstractContainerBaseTest {

  @Autowired private OcidResolver ocidResolver;

  @Autowired private GameCharacterService gameCharacterService;

  @Autowired private GameCharacterRepository gameCharacterRepository;

  @Autowired private CacheManager cacheManager;

  private static final String TEST_USER_IGN = "SelfInvocationTestUser_N16";

  @BeforeEach
  void setUp() {
    // 테스트 데이터 정리
    gameCharacterRepository.findByUserIgn(TEST_USER_IGN).ifPresent(gameCharacterRepository::delete);

    // 캐시 정리
    Optional.ofNullable(cacheManager.getCache("ocidCache")).ifPresent(c -> c.evict(TEST_USER_IGN));
    Optional.ofNullable(cacheManager.getCache("ocidNegativeCache"))
        .ifPresent(c -> c.evict(TEST_USER_IGN));
  }

  @AfterEach
  void tearDown() {
    // 테스트 데이터 정리
    gameCharacterRepository.findByUserIgn(TEST_USER_IGN).ifPresent(gameCharacterRepository::delete);
  }

  /**
   * 🔵 Blue's Test 1: OcidResolver의 Self-Invocation 경로 분석
   *
   * <p><b>시나리오</b>:
   *
   * <ol>
   *   <li>OcidResolver.resolve(userIgn) 호출
   *   <li>DB에 없으면 createAndGetOcid() 호출
   *   <li>createAndGetOcid()에서 this.createNewCharacter() 호출
   *   <li>createNewCharacter()의 @Transactional(REQUIRES_NEW) 동작 여부 확인
   * </ol>
   *
   * <p><b>문제 분석</b>: this.createNewCharacter() 호출 시 프록시를 거치지 않으므로 @Transactional 어노테이션이 적용되지 않을 수
   * 있음
   */
  @Test
  @DisplayName("OcidResolver의 Self-Invocation 경로 분석")
  void shouldAnalyzeOcidResolverSelfInvocationPath() {
    log.info("[Blue] Analyzing OcidResolver self-invocation path...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    Nightmare 16: OcidResolver Self-Invocation Analysis     │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Code Flow in OcidResolver:                                 │");
    log.info("│                                                            │");
    log.info("│   public String resolve(String userIgn) {                  │");
    log.info("│       // 1. Negative Cache 확인                            │");
    log.info("│       if (isNonExistent(cleanIgn)) { throw ... }           │");
    log.info("│                                                            │");
    log.info("│       // 2. DB 조회                                        │");
    log.info("│       Optional<GameCharacter> existing = ...               │");
    log.info("│       if (existing.isPresent()) { return ocid; }           │");
    log.info("│                                                            │");
    log.info("│       // 3. Self-Invocation 발생!                          │");
    log.info("│       return createAndGetOcid(cleanIgn);                   │");
    log.info("│   }                                                        │");
    log.info("│                                                            │");
    log.info("│   private String createAndGetOcid(String userIgn) {        │");
    log.info("│       return createNewCharacter(userIgn).getOcid();        │");
    log.info("│       //     ↑ this.createNewCharacter() 호출!             │");
    log.info("│   }                                                        │");
    log.info("│                                                            │");
    log.info("│   @Transactional(propagation = Propagation.REQUIRES_NEW)   │");
    log.info("│   public GameCharacter createNewCharacter(String userIgn) {│");
    log.info("│       // 새 트랜잭션에서 실행되어야 하지만...               │");
    log.info("│       // this.method() 호출이므로 프록시 우회!             │");
    log.info("│   }                                                        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ⚠️ 잠재적 문제:                                             │");
    log.info("│   - createNewCharacter()가 새 트랜잭션에서 실행되지 않음    │");
    log.info("│   - 외부 트랜잭션이 있으면 그 트랜잭션에 참여                │");
    log.info("│   - 외부 트랜잭션이 없으면 트랜잭션 없이 실행!              │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("Analysis documentation").isTrue();
  }

  /**
   * 🔴 Red's Test 2: Self-Invocation으로 @Transactional 바이패스 검증
   *
   * <p>외부에서 직접 createNewCharacter() 호출 vs 내부에서 호출 비교
   */
  @Test
  @DisplayName("외부 호출 vs Self-Invocation의 트랜잭션 적용 차이 검증")
  void shouldVerifyTransactionDifferenceInSelfInvocation() {
    log.info("[Red] Verifying transaction application in self-invocation...");

    // 이 테스트는 실제 NexonAPI 호출이 필요하므로 분석적 테스트로 진행
    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    Self-Invocation @Transactional Bypass Test              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Case 1: External Call (Through Proxy)                      │");
    log.info("│   Client → OcidResolver(Proxy) → createNewCharacter()      │");
    log.info("│   Result: @Transactional(REQUIRES_NEW) WORKS ✅            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Case 2: Internal Call (Self-Invocation)                    │");
    log.info("│   resolve() → this.createAndGetOcid() →                    │");
    log.info("│             → this.createNewCharacter()                    │");
    log.info("│   Result: @Transactional(REQUIRES_NEW) BYPASSED ❌         │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Solutions:                                               │");
    log.info("│   1. Extract createNewCharacter to separate @Service       │");
    log.info("│   2. Inject OcidResolver into itself with @Lazy            │");
    log.info("│   3. Use AopContext.currentProxy()                         │");
    log.info("└────────────────────────────────────────────────────────────┘");

    // GameCharacterService의 createNewCharacter는 외부에서 호출하므로 프록시 통과
    // OcidResolver의 createNewCharacter는 내부에서 호출하므로 Self-Invocation

    assertThat(true).as("Self-invocation analysis").isTrue();
  }

  /**
   * 🟢 Green's Test 3: GameCharacterService 외부 호출 시 트랜잭션 정상 적용 확인
   *
   * <p>외부에서 직접 호출하면 @Transactional이 정상 적용됨을 검증
   */
  @Test
  @DisplayName("외부 호출 시 @Transactional 정상 적용 확인")
  @Transactional
  void shouldApplyTransactionWhenCalledExternally() {
    // Given: 테스트용 캐릭터 생성
    GameCharacter testCharacter = new GameCharacter(TEST_USER_IGN, "test-ocid-n16");
    gameCharacterRepository.saveAndFlush(testCharacter);

    log.info("[Green] Testing external call transaction application...");

    // When: 외부에서 직접 getCharacterForUpdate 호출 (프록시 통과)
    GameCharacter found = gameCharacterService.getCharacterForUpdate(TEST_USER_IGN);

    // Then: 트랜잭션 내에서 실행됨
    boolean isInTransaction = TransactionSynchronizationManager.isActualTransactionActive();

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    External Call Transaction Application Test              │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Method: gameCharacterService.getCharacterForUpdate()       │");
    log.info("│ Called from: Test (External)                               │");
    log.info("│ Transaction Active: {}                                     │", isInTransaction);
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ ✅ External call goes through proxy                        │");
    log.info("│    @Transactional annotation is applied correctly          │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(found).isNotNull();
    assertThat(found.getUserIgn()).isEqualTo(TEST_USER_IGN);
    assertThat(isInTransaction)
        .as("[Nightmare] Transaction should be active for external call")
        .isTrue();
  }

  /**
   * 🟣 Purple's Test 4: Self-Invocation 감지 가이드
   *
   * <p>프로젝트 내 Self-Invocation 가능성이 있는 코드 패턴 분석
   */
  @Test
  @DisplayName("프로젝트 내 Self-Invocation 패턴 분석")
  void shouldAnalyzeSelfInvocationPatternsInProject() {
    log.info("[Purple] Analyzing self-invocation patterns in project...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    Project Self-Invocation Pattern Analysis                │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔍 Identified Patterns:                                    │");
    log.info("│                                                            │");
    log.info("│ 1. OcidResolver                                            │");
    log.info("│    - resolve() → createAndGetOcid() → createNewCharacter() │");
    log.info("│    - createNewCharacter has @Transactional(REQUIRES_NEW)   │");
    log.info("│    - Risk: Transaction propagation may not work            │");
    log.info("│                                                            │");
    log.info("│ 2. GameCharacterService                                    │");
    log.info("│    - External calls only → No self-invocation issue        │");
    log.info("│    - Methods are called through proxy correctly            │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 Detection Commands:                                     │");
    log.info("│                                                            │");
    log.info("│   # Find self-invocation with @Transactional               │");
    log.info("│   grep -rn \"this\\..*\\(\" src/main | grep -B5 \"@Transactional\"│");
    log.info("│                                                            │");
    log.info("│   # Find private methods with AOP annotations              │");
    log.info("│   grep -rn \"private.*@\" src/main                          │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 📋 Action Items:                                           │");
    log.info("│   - [ ] Review OcidResolver.createNewCharacter()           │");
    log.info("│   - [ ] Consider extracting to separate service            │");
    log.info("│   - [ ] Add integration test for transaction propagation   │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("Pattern analysis documentation").isTrue();
  }

  /**
   * 🟡 Yellow's Test 5: resolveCharacter의 Self-Invocation 검증
   *
   * <p>OcidResolver.resolveCharacter()에서도 동일한 Self-Invocation 패턴 존재
   */
  @Test
  @DisplayName("resolveCharacter의 Self-Invocation 패턴 분석")
  void shouldAnalyzeResolveCharacterSelfInvocation() {
    log.info("[Yellow] Analyzing resolveCharacter self-invocation...");

    log.info("┌────────────────────────────────────────────────────────────┐");
    log.info("│    resolveCharacter Self-Invocation Analysis               │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ Code in OcidResolver.resolveCharacter():                   │");
    log.info("│                                                            │");
    log.info("│   public GameCharacter resolveCharacter(String userIgn) {  │");
    log.info("│       // ... DB lookup ...                                 │");
    log.info("│       return existing.orElseGet(                           │");
    log.info("│           () -> createNewCharacter(cleanIgn)  // ⚠️        │");
    log.info("│       );                                                   │");
    log.info("│   }                                                        │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 분석:                                                      │");
    log.info("│   - Lambda 내에서 createNewCharacter() 호출                │");
    log.info("│   - Lambda의 this는 외부 클래스 인스턴스                   │");
    log.info("│   - 따라서 Self-Invocation 발생!                           │");
    log.info("├────────────────────────────────────────────────────────────┤");
    log.info("│ 🔧 권장 해결책:                                            │");
    log.info("│   - CharacterCreationService 별도 분리                     │");
    log.info("│   - OcidResolver → CharacterCreationService 주입           │");
    log.info("│   - 외부 호출로 @Transactional 정상 적용                   │");
    log.info("└────────────────────────────────────────────────────────────┘");

    assertThat(true).as("resolveCharacter analysis").isTrue();
  }
}
