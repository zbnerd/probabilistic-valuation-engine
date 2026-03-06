# PostgreSQL Migration - GitHub Issues

## Phase 0: Foundation

### Issue P0-01: Project Setup + Kotlin Conversion Foundation

**Title:** `[P0] 프로젝트 초기 설정 + Kotlin 변환 기반`

**Description:**
v2/postgresql-redesign 브랜치를 생성하고, Java → Kotlin 변환의 기반을 마련한다.

**Tasks:**
- [ ] `develop` 브랜치에서 `v2/postgresql-redesign` 분기
- [ ] build.gradle.kts에 Kotlin 플러그인 추가 (jvm, jpa, spring)
- [ ] Kotlin 컴파일러 옵션 설정 (jvmTarget = 21)
- [ ] ktlint 설정 (.editorconfig, build.gradle.kts)
- [ ] Java 파일을 Kotlin으로 변환할 때 사용할 IntelliJ 설정 가이드 작성
- [ ] 삭제 대상 파일 목록 작성 (docs/migration/deletion-targets.md)

**Acceptance Criteria:**
- [ ] v2/postgresql-redesign 브랜치 생성 완료
- [ ] Kotlin 플러그인 추가 후 `./gradlew build -x test` 성공
- [ ] ktlint check 통과
- [ ] 삭제 대상 파일 목록 문서화

**Related Modules:** buildSrc, all modules (gradle config)
**Effort:** M
**Blocked by:** None

---

### Issue P0-02: PostgreSQL + PGMQ Docker Compose Setup

**Title:** `[P0] PostgreSQL + PGMQ Docker Compose 설정`

**Description:**
로컬 개발환경용 PostgreSQL + PGMQ Extension이 포함된 Docker Compose를 구성한다.

**Tasks:**
- [ ] docker-compose.yml에서 MySQL, MongoDB, Redis 제거
- [ ] PostgreSQL 16 + PGMQ Extension 이미지 추가 (custom Dockerfile 또는 pgmq/pgmq)
- [ ] 초기 스키마 스크립트 (init.sql) 작성
- [ ] application-local.yml에 PostgreSQL 연결 설정
- [ ] Testcontainers 설정 (재사용 모드)
- [ ] 로컬 DB 접속 가이드 작성

**Acceptance Criteria:**
- [ ] `docker-compose up -d`로 PostgreSQL + PGMQ 실행
- [ ] `psql`로 PGMQ Extension 설치 확인 (`SELECT * FROM pgmq_meta();`)
- [ ] Spring Boot 앱이 PostgreSQL에 연결 성공
- [ ] Testcontainers 재사용 모드 동작 확인

**Related Modules:** module-app, module-infra
**Effort:** M
**Blocked by:** None

---

## Phase 1: Core Data Layer

### Issue P1-01: ADR-001 PostgreSQL Single DB Strategy

**Title:** `[P1] ADR-001 PostgreSQL 단일 DB 전략`

**Description:**
MySQL + MongoDB + Redis를 PostgreSQL 단일 DB로 통합한 결정 근거를 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-001 문서 작성 (docs/adr/001-postgresql-single-db-strategy.md)
- [ ] 기존 아키텍처 분석 (MySQL, MongoDB, Redis 사용 패턴)
- [ ] PostgreSQL 통합 근거:
  - jsonb로 비정형 장비 데이터 저장 (MongoDB 대체)
  - PGMQ로 메시지 큐 (Redis Streams 대체)
  - Advisory Lock로 분산 락 (Redisson 대체)
  - UNLOGGED TABLE로 버퍼 (Redis buffer 대체)
- [ ] 트레이드오프 분석 (vs 기존 아키텍처)
- [ ] 스케일아웃 트리거 조건 정의

**Acceptance Criteria:**
- [ ] ADR-001 문서 완료 (템플릿 준수)
- [ ] Status: Accepted
- [ ] 관련 ADR/문서 링크 포함

**Related Modules:** docs/adr
**Effort:** M
**Blocked by:** None

---

### Issue P1-02: Domain Entities (PostgreSQL + jsonb)

**Title:** `[P1] 도메인 엔티티 정의 (PostgreSQL + jsonb)`

**Description:**
기존 JPA Entity를 PostgreSQL jsonb를 활용한 형태로 재설계하고 Kotlin으로 변환한다.

**Tasks:**
- [ ] 기존 Entity 분석: GameCharacter, Member, CubeProbability, EquipmentExpectationSummary
- [ ] PostgreSQL 스키마 설계:
  ```sql
  CREATE TABLE game_characters (
    id BIGSERIAL PRIMARY KEY,
    ocid VARCHAR(64) UNIQUE NOT NULL,
    character_name VARCHAR(64) NOT NULL,
    world_name VARCHAR(32) NOT NULL,
    equipment_data JSONB,          -- 장비 데이터 (비정형)
    calculation_result JSONB,      -- 계산 결과 (캐시)
    last_collected_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
  );
  
  CREATE TABLE members (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(64) UNIQUE NOT NULL,
    nickname VARCHAR(64),
    role VARCHAR(16) DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT NOW()
  );
  ```
- [ ] Kotlin data class로 Entity 재작성
- [ ] jsonb 컬럼 매핑 (JPA AttributeConverter 또는 Hibernate Types)
- [ ] 기존 GZIP 압축 로직 제거 (jsonb 압축은 PostgreSQL에 위임)

**Acceptance Criteria:**
- [ ] 모든 Entity Kotlin data class로 변환
- [ ] jsonb 컬럼 매핑 동작 확인
- [ ] 기존 Entity와 동일한 도메인 표현

**Related Modules:** module-core, module-infra
**Effort:** L
**Blocked by:** P1-01

---

### Issue P1-03: Repository Layer + Ports

**Title:** `[P1] Repository 레이어 + Port 인터페이스`

**Description:**
헥사고날 아키텍처에 맞게 Port 인터페이스와 Repository 구현체를 분리하고 Kotlin으로 변환한다.

**Tasks:**
- [ ] module-core에 Port 인터페이스 정의:
  - `GameCharacterPort` (Load, Save, Find)
  - `MemberPort`
  - `ExpectationResultPort`
- [ ] module-infra에 Repository 구현체:
  - `GameCharacterRepository` (JpaRepository)
  - `JooqGameCharacterRepository` (복잡한 쿼리용)
- [ ] 기존 Repository 코드 Kotlin 변환
- [ ] jsonb 쿼리 메서드 추가 (`@Query` with native SQL)

**Acceptance Criteria:**
- [ ] Port 인터페이스가 module-core에 위치
- [ ] Repository 구현체가 module-infra에 위치
- [ ] 기존 테스트 통과 (Repository 단위 테스트)

**Related Modules:** module-core, module-infra
**Effort:** M
**Blocked by:** P1-02

---

## Phase 2: Message Queue

### Issue P2-01: ADR-002 PGMQ Integration

**Title:** `[P2] ADR-002 PGMQ 도입`

**Description:**
Redis Streams + Outbox 패턴을 PGMQ로 대체한 결정 근거를 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-002 문서 작성 (docs/adr/002-pgmq-integration.md)
- [ ] 기존 Outbox 패턴 분석 (EventOutbox, DonationOutbox, NexonApiOutbox)
- [ ] PGMQ 장점:
  - 동일 트랜잭션 내 메시지 발행 (Outbox 불필요)
  - SKIP LOCKED 기반 경쟁 소비
  - PostgreSQL 네이티브 (추가 인프라 불필요)
- [ ] Redis Streams vs PGMQ 비교
- [ ] 메시지 스키마 설계

**Acceptance Criteria:**
- [ ] ADR-002 문서 완료
- [ ] PGMQ 큐 스키마 정의
- [ ] 메시지 프로듀서/컨슈머 인터페이스 설계

**Related Modules:** docs/adr, module-core
**Effort:** M
**Blocked by:** P1-03

---

### Issue P2-02: PGMQ Producers & Consumers

**Title:** `[P2] PGMQ 프로듀서 & 컨슈머 구현`

**Description:**
PGMQ 기반 메시지 큐의 프로듀서와 컨슈머를 구현한다. 기존 Outbox 스케줄러를 대체한다.

**Tasks:**
- [ ] PGMQ 클라이언트 구현 (JDBC 기반):
  ```kotlin
  @Repository
  class PgmqMessageQueue(
    private val jdbcTemplate: JdbcTemplate
  ) {
    fun send(queue: String, message: Any) {
      jdbcTemplate.update(
        "SELECT pgmq.send(?, ?::jsonb)",
        queue, objectMapper.writeValueAsString(message)
      )
    }
    
    fun read(queue: String, limit: Int = 10): List<PgmqMessage> {
      return jdbcTemplate.query(
        "SELECT * FROM pgmq.read(?, ?, ?)",
        // ...
      )
    }
    
    fun archive(queue: String, messageId: Long) {
      jdbcTemplate.update("SELECT pgmq.archive(?, ?)", queue, messageId)
    }
  }
  ```
- [ ] 큐 정의:
  - `calculation_queue` (기대값 계산 요청)
  - `like_sync_queue` (좋아요 동기화)
  - `donation_queue` (후원 처리)
- [ ] 컨슈머 스케줄러 구현 (SKIP LOCKED 기반 poll)
- [ ] 기존 Outbox 스케줄러 3개 제거

**Acceptance Criteria:**
- [ ] PGMQ 프로듀서로 메시지 발행 성공
- [ ] 컨슈머가 메시지 처리 후 archive 성공
- [ ] 기존 Outbox 관련 코드 전부 삭제

**Related Modules:** module-infra, module-app
**Effort:** L
**Blocked by:** P2-01

---

## Phase 3: Locking & Caching

### Issue P3-01: ADR-003 Advisory Lock (Redisson Replacement)

**Title:** `[P3] ADR-003 Advisory Lock 도입`

**Description:**
Redisson 분산 락을 PostgreSQL Advisory Lock로 대체한 결정 근거를 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-003 문서 작성 (docs/adr/003-advisory-lock-integration.md)
- [ ] 기존 Redisson 사용 패턴 분석:
  - Single Flight (Leader Latch)
  - 좋아요 버퍼 동기화 락
  - Rate Limiting
- [ ] Advisory Lock 장점:
  - DB 내장 (추가 인프라 불필요)
  - 세션 수명 주기와 연동 (자동 해제)
  - 교착 상태 방지 (tryLock with timeout)
- [ ] Advisory Lock 제약사항:
  - DB 연결 풀 고갈 시 락 부족
  - 장시간 락 보유 시 연결 점유
- [ ] 해결책: 짧은 락 보유 + 재시도

**Acceptance Criteria:**
- [ ] ADR-003 문서 완료
- [ ] Advisory Lock 인터페이스 설계
- [ ] Redisson 제거 계획 수립

**Related Modules:** docs/adr, module-core
**Effort:** M
**Blocked by:** P2-02

---

### Issue P3-02: Caffeine Cache (Redis Cache Replacement)

**Title:** `[P3] Caffeine 캐시 (Redis 캐시 대체)`

**Description:**
Redis 분산 캐시를 Caffeine 로컬 캐시로 대체하고, 캐시 무효화 전략을 재설계한다.

**Tasks:**
- [ ] 기존 TieredCache 분석 (L1 Caffeine + L2 Redis)
- [ ] Caffeine 단일 계층으로 변경:
  ```kotlin
  @Configuration
  class CacheConfig {
    @Bean
    fun cacheManager(): CacheManager {
      val builder = CaffeineCacheManager()
      builder.setCaffeine(Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(30))
        .recordStats())
      return builder
    }
  }
  ```
- [ ] 캐시 무효화 전략:
  - TTL 기반 (30분)
  - 수동 무효화 (장비 갱신 시)
- [ ] 인기 캐릭터 캐시 워밍업 스케줄러 유지
- [ ] Redis 설정 및 Redisson 의존성 제거

**Acceptance Criteria:**
- [ ] Caffeine 캐시 동작 확인
- [ ] 캐시 히트율 85%+ 유지
- [ ] Redis 관련 설정/의존성 완전 제거

**Related Modules:** module-infra, module-app
**Effort:** M
**Blocked by:** P3-01

---

## Phase 4: Data Pipeline

### Issue P4-01: ADR-004 Collect/Compute/Serve Separation

**Title:** `[P4] ADR-004 수집/계산/서빙 분리 전략`

**Description:**
데이터 파이프라인을 수집(Collect) / 계산(Compute) / 서빙(Serve)으로 분리한 전략을 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-004 문서 작성 (docs/adr/004-pipeline-separation.md)
- [ ] 파이프라인 흐름:
  ```
  Collect: Nexon API → PostgreSQL (JSONB)
           └─ 스케줄러 기반 배치 수집
  
  Compute: PGMQ Queue → Worker → Pre-computed Table
           └─ 비동기 계산 (CPU 바운드, 2초/건)
  
  Serve:   Caffeine Cache → Pre-computed Table
           └─ 빠른 응답 (< 10ms)
  ```
- [ ] 각 단계의 책임과 인터페이스 정의
- [ ] 실패 시 복구 전략

**Acceptance Criteria:**
- [ ] ADR-004 문서 완료
- [ ] 시퀀스 다이어그램 포함
- [ ] 각 단계의 인터페이스 정의

**Related Modules:** docs/adr
**Effort:** M
**Blocked by:** P3-02

---

### Issue P4-02: Nexon API Collector (→ PostgreSQL)

**Title:** `[P4] Nexon API 수집기 (→ PostgreSQL)`

**Description:**
기존 Nexon API 수집 로직을 PostgreSQL 저장으로 변경하고, Outbox 패턴을 PGMQ로 대체한다.

**Tasks:**
- [ ] 기존 NexonApiOutboxScheduler 분석
- [ ] 새로운 수집 파이프라인:
  ```kotlin
  @Service
  class NexonDataCollector(
    private val nexonApiClient: NexonApiClient,
    private val gameCharacterRepository: GameCharacterRepository,
    private val pgmq: PgmqMessageQueue
  ) {
    @Scheduled(fixedRate = 300_000) // 5분
    fun collect() {
      val targets = findStaleCharacters()
      targets.forEach { character ->
        val equipment = nexonApiClient.fetchEquipment(character.ocid)
        gameCharacterRepository.updateEquipmentData(character.id, equipment)
        pgmq.send("calculation_queue", CalculationRequest(character.id))
      }
    }
  }
  ```
- [ ] Rate Limiting 유지 (Nexon API 제한 준수)
- [ ] 에러 처리 (Circuit Breaker 유지)
- [ ] 기존 NexonApiOutbox 관련 코드 삭제

**Acceptance Criteria:**
- [ ] Nexon API 데이터 수집 → PostgreSQL 저장
- [ ] PGMQ로 계산 요청 발행
- [ ] 기존 Outbox 관련 코드 삭제

**Related Modules:** module-infra, module-app
**Effort:** L
**Blocked by:** P4-01

---

### Issue P4-03: Expectation Calculation Workers (PGMQ-based)

**Title:** `[P4] 기대값 계산 워커 (PGMQ 기반)`

**Description:**
PGMQ 큐에서 계산 요청을 소비하고, 기대값을 계산하여 사전 계산 테이블에 저장하는 워커를 구현한다.

**Tasks:**
- [ ] 계산 워커 구현:
  ```kotlin
  @Service
  class ExpectationCalculationWorker(
    private val pgmq: PgmqMessageQueue,
    private val starforceEngine: StarforceCalculationEngine,
    private val cubeEngine: CubeCalculationEngine,
    private val flameEngine: FlameCalculationEngine,
    private val resultRepository: ExpectationResultRepository
  ) {
    @Scheduled(fixedRate = 1_000)
    fun processCalculations() {
      val messages = pgmq.read("calculation_queue", limit = 10)
      messages.forEach { msg ->
        val request = parseCalculationRequest(msg)
        val result = calculateExpectation(request)
        resultRepository.save(result)
        pgmq.archive("calculation_queue", msg.id)
      }
    }
  }
  ```
- [ ] 기존 계산 엔진 Kotlin 변환 (Starforce, Cube, Flame)
- [ ] 사전 계산 테이블 스키마:
  ```sql
  CREATE TABLE expectation_results (
    id BIGSERIAL PRIMARY KEY,
    character_id BIGINT REFERENCES game_characters(id),
    equipment_slot VARCHAR(32),
    starforce_result JSONB,
    cube_result JSONB,
    flame_result JSONB,
    calculated_at TIMESTAMP DEFAULT NOW()
  );
  ```
- [ ] 워커 스케일아웃 지원 (여러 인스턴스가 경쟁 소비)

**Acceptance Criteria:**
- [ ] PGMQ 메시지 소비 → 계산 → 저장 플로우 동작
- [ ] 계산 엔진 기존 테스트 통과
- [ ] 다중 워커 인스턴스 테스트 통과

**Related Modules:** module-core, module-infra, module-app
**Effort:** L
**Blocked by:** P4-02

---

## Phase 5: API Layer

### Issue P5-01: REST Controllers (Adapt to New Repository)

**Title:** `[P5] REST 컨트롤러 (새 Repository 적용)`

**Description:**
기존 REST Controller를 새로운 Repository 구조에 맞게 수정하고 Kotlin으로 변환한다.

**Tasks:**
- [ ] 기존 Controller 분석 (V1, V4, V5)
- [ ] 새로운 Repository Port 주입
- [ ] 응답 DTO 수정 (필요시)
- [ ] Kotlin으로 변환
- [ ] 기존 Controller 테스트 수정

**Acceptance Criteria:**
- [ ] 모든 Controller Kotlin 변환
- [ ] API 엔드포인트 동작 확인
- [ ] 기존 API 스펙 유지 (호환성)

**Related Modules:** module-web
**Effort:** M
**Blocked by:** P4-03

---

### Issue P5-02: ADR-005 Single Flight + Hot Key Handling

**Title:** `[P5] ADR-005 Single Flight + 핫 키 대응`

**Description:**
인기 캐릭터 바이럴 시 동일 요청 중복 처리를 방지하는 Single Flight 패턴을 Advisory Lock로 구현한 결정 근거를 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-005 문서 작성 (docs/adr/005-single-flight-hot-key.md)
- [ ] Single Flight 구현:
  ```kotlin
  @Service
  class SingleFlight(
    private val jdbcTemplate: JdbcTemplate
  ) {
    fun <T> execute(key: String, loader: () -> T): T {
      val lockKey = key.hashCode().toLong()
      val acquired = jdbcTemplate.queryForObject(
        "SELECT pg_try_advisory_lock(?)",
        Boolean::class.java, lockKey
      )
      
      return if (acquired) {
        try {
          loader()
        } finally {
          jdbcTemplate.update("SELECT pg_advisory_unlock(?)", lockKey)
        }
      } else {
        // Wait and retry from cache
        waitForResult(key)
      }
    }
  }
  ```
- [ ] 핫 키 시나리오 분석 (3000 동시 요청 → 1회 계산)
- [ ] Caffeine 캐시와의 연동

**Acceptance Criteria:**
- [ ] ADR-005 문서 완료
- [ ] Single Flight 구현 동작 확인
- [ ] 동시 요청 99% 중복 제거 확인

**Related Modules:** docs/adr, module-infra
**Effort:** M
**Blocked by:** P5-01

---

## Phase 6: Features

### Issue P6-01: Like System (PostgreSQL UNLOGGED + PGMQ)

**Title:** `[P6] 좋아요 시스템 (PostgreSQL UNLOGGED + PGMQ)`

**Description:**
기존 Redis 기반 좋아요 버퍼를 PostgreSQL UNLOGGED TABLE로 대체하고, PGMQ로 동기화한다.

**Tasks:**
- [ ] UNLOGGED TABLE 스키마:
  ```sql
  CREATE UNLOGGED TABLE like_buffer (
    id BIGSERIAL PRIMARY KEY,
    character_ocid VARCHAR(64) NOT NULL,
    user_account_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    UNIQUE(character_ocid, user_account_id)
  );
  
  CREATE TABLE like_counts (
    character_ocid VARCHAR(64) PRIMARY KEY,
    count BIGINT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT NOW()
  );
  ```
- [ ] 좋아요 서비스 Kotlin 변환:
  - 자기 자신 좋아요 방지 로직 유지
  - 중복 좋아요 감지 로직 유지
- [ ] 동기화 스케줄러 (PGMQ 기반)
- [ ] 기존 LikeSyncScheduler, LikeBufferStrategy 제거

**Acceptance Criteria:**
- [ ] 좋아요 추가/취소 동작
- [ ] UNLOGGED TABLE → 정식 TABLE 동기화 동작
- [ ] 기존 Redis 관련 코드 삭제

**Related Modules:** module-core, module-infra, module-app
**Effort:** L
**Blocked by:** P5-02

---

### Issue P6-02: Donation System (PostgreSQL + PGMQ)

**Title:** `[P6] 후원 시스템 (PostgreSQL + PGMQ)`

**Description:**
기존 DonationOutbox를 PGMQ로 대체하고, 후원 처리 파이프라인을 재구현한다.

**Tasks:**
- [ ] 후원 테이블 스키마:
  ```sql
  CREATE TABLE donations (
    id BIGSERIAL PRIMARY KEY,
    donor_account_id VARCHAR(64) NOT NULL,
    amount INTEGER NOT NULL,
    message TEXT,
    status VARCHAR(16) DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT NOW(),
    processed_at TIMESTAMP
  );
  ```
- [ ] 후원 서비스 Kotlin 변환
- [ ] PGMQ 기반 후원 처리 워커
- [ ] 기존 DonationOutbox 관련 코드 삭제

**Acceptance Criteria:**
- [ ] 후원 요청 → PGMQ 발행 → 처리 완료 플로우
- [ ] 기존 Outbox 관련 코드 삭제

**Related Modules:** module-core, module-infra, module-app
**Effort:** M
**Blocked by:** P6-01

---

### Issue P6-03: JWT Authentication (Keep, Convert to Kotlin)

**Title:** `[P6] JWT 인증 (유지, Kotlin 변환)`

**Description:**
기존 JWT 인증 로직을 유지하면서 Kotlin으로 변환한다.

**Tasks:**
- [ ] JwtTokenProvider Kotlin 변환
- [ ] JwtPayload Kotlin 변환
- [ ] FingerprintGenerator Kotlin 변환
- [ ] AccountIdGenerator Kotlin 변환
- [ ] Security Filter Kotlin 변환
- [ ] 기존 인증 테스트 통과 확인

**Acceptance Criteria:**
- [ ] JWT 발급/검증 동작
- [ ] 기존 인증 테스트 통과

**Related Modules:** module-infra, module-web
**Effort:** M
**Blocked by:** None (병렬 가능)

---

## Phase 7: Testing

### Issue P7-01: Integration Tests (Testcontainers + PGMQ)

**Title:** `[P7] 통합 테스트 (Testcontainers + PGMQ)`

**Description:**
PostgreSQL + PGMQ 환경에서 통합 테스트를 작성한다.

**Tasks:**
- [ ] Testcontainers 설정 (재사용 모드):
  ```kotlin
  @Testcontainers
  abstract class BaseIntegrationTest {
    companion object {
      @Container
      @JvmStatic
      val postgres: PostgreSQLContainer<*> = PostgreSQLContainer<*>("pgmq/pgmq:latest")
        .withReuse(true)
    }
  }
  ```
- [ ] PGMQ 통합 테스트
- [ ] Repository 통합 테스트
- [ ] API 통합 테스트
- [ ] 기존 통합 테스트 마이그레이션

**Acceptance Criteria:**
- [ ] Testcontainers로 격리된 테스트 환경
- [ ] PGMQ 메시지 큐 테스트 통과
- [ ] 모든 통합 테스트 통과

**Related Modules:** All modules (test)
**Effort:** L
**Blocked by:** P6-03

---

### Issue P7-02: Chaos Tests (PostgreSQL Failure Scenarios)

**Title:** `[P7] 카오스 테스트 (PostgreSQL 장애 시나리오)`

**Description:**
PostgreSQL 장애 상황에서 시스템의 회복 탄력성을 테스트한다.

**Tasks:**
- [ ] Toxiproxy 설정 (PostgreSQL 장애 주입)
- [ ] 장애 시나리오:
  - DB 연결 끊김
  - DB 지연 (latency 주입)
  - DB 과부하 (connection pool 고갈)
  - PGMQ 큐 적체
- [ ] Circuit Breaker 동작 확인
- [ ] Fallback 동작 확인
- [ ] Graceful Shutdown 테스트

**Acceptance Criteria:**
- [ ] 모든 장애 시나리오 테스트 통과
- [ ] Circuit Breaker Open → Half-Open → Closed 전환 확인

**Related Modules:** module-chaos-test
**Effort:** L
**Blocked by:** P7-01

---

## Phase 8: Performance

### Issue P8-01: ADR-006 Scale-out Strategy

**Title:** `[P8] ADR-006 스케일아웃 전략`

**Description:**
PostgreSQL 단일 인스턴스 한계 도달 시 스케일아웃 전략과 Redis 재도입 트리거 조건을 ADR로 문서화한다.

**Tasks:**
- [ ] ADR-006 문서 작성 (docs/adr/006-scaleout-strategy.md)
- [ ] PostgreSQL 성능 한계 분석:
  - 커넥션 풀 크기
  - 쿼리 성능
  - PGMQ 처리량
- [ ] 스케일아웃 단계:
  1. PostgreSQL Vertical Scale (CPU/RAM 증설)
  2. Read Replica 추가
  3. Redis 캐시 재도입 (L2)
  4. 샤딩
- [ ] Redis 재도입 트리거 조건:
  - QPS > 2,000
  - p99 Latency > 500ms
  - PostgreSQL CPU > 80%

**Acceptance Criteria:**
- [ ] ADR-006 문서 완료
- [ ] 스케일아웃 의사결정 트리 포함

**Related Modules:** docs/adr
**Effort:** M
**Blocked by:** P7-02

---

### Issue P8-02: Load Testing + Optimization

**Title:** `[P8] 부하 테스트 + 최적화`

**Description:**
wrk 또는 Gatling으로 부하 테스트를 수행하고, 성능을 최적화한다.

**Tasks:**
- [ ] 부하 테스트 시나리오:
  - 평시: 0.5 QPS
  - 패치일: 500 QPS
  - 바이럴: 2,000 QPS
- [ ] Before/After 메트릭 수집:
  - Throughput (RPS)
  - p50/p95/p99 Latency
  - PostgreSQL 커넥션 수
  - CPU/Memory 사용량
- [ ] 병목 지점 분석 및 최적화
- [ ] 인덱스 최적화
- [ ] 쿼리 최적화

**Acceptance Criteria:**
- [ ] 500 QPS에서 p99 < 200ms
- [ ] Before/After 비교 리포트 작성

**Related Modules:** All modules
**Effort:** L
**Blocked by:** P8-01

---

## Phase 9: Deployment

### Issue P9-01: CI/CD Pipeline Updates

**Title:** `[P9] CI/CD 파이프라인 업데이트`

**Description:**
PostgreSQL 기반 아키텍처에 맞게 CI/CD 파이프라인을 수정한다.

**Tasks:**
- [ ] GitHub Actions 워크플로우 수정:
  - PostgreSQL 서비스 컨테이너 추가
  - PGMQ Extension 설치
- [ ] 배포 파이프라인 수정:
  - Blue-Green 배포 유지
  - DB 마이그레이션 스크립트 (Flyway)
- [ ] 환경 변수 정리 (Redis 제거)

**Acceptance Criteria:**
- [ ] CI 파이프라인 통과
- [ ] CD 파이프라인 동작 확인

**Related Modules:** .github/workflows
**Effort:** M
**Blocked by:** P8-02

---

### Issue P9-02: Monitoring + Runbook Updates

**Title:** `[P9] 모니터링 + Runbook 업데이트`

**Description:**
새로운 아키텍처에 맞게 모니터링 대시보드와 Runbook을 업데이트한다.

**Tasks:**
- [ ] Grafana 대시보드 수정:
  - PostgreSQL 메트릭 추가
  - PGMQ 큐 깊이
  - Advisory Lock 대기 시간
  - Redis 메트릭 제거
- [ ] Prometheus 메트릭 수정
- [ ] Alert 규칙 수정
- [ ] Runbook 업데이트:
  - PostgreSQL 장애 대응
  - PGMQ 큐 적체 대응
  - Connection Pool 고갈 대응

**Acceptance Criteria:**
- [ ] 모니터링 대시보드 동작
- [ ] Runbook 업데이트 완료

**Related Modules:** module-app, docs
**Effort:** M
**Blocked by:** P9-01

---

## Summary

| Phase | Issues | Effort | Total |
|-------|--------|--------|-------|
| P0 | 2 | M, M | 2 |
| P1 | 3 | M, L, M | 3 |
| P2 | 2 | M, L | 2 |
| P3 | 2 | M, M | 2 |
| P4 | 3 | M, L, L | 3 |
| P5 | 2 | M, M | 2 |
| P6 | 3 | L, M, M | 3 |
| P7 | 2 | L, L | 2 |
| P8 | 2 | M, L | 2 |
| P9 | 2 | M, M | 2 |
| **Total** | **23** | | **23** |

## Dependency Graph

```
P0-01 ──────────────────────────────────────────────────────────┐
P0-02 ──────────────────────────────────────────────────────────┤
                                                                 ▼
P1-01 ──► P1-02 ──► P1-03 ──► P2-01 ──► P2-02 ──► P3-01 ──► P3-02
                                                        │
                                                        ▼
                                        P4-01 ──► P4-02 ──► P4-03
                                                        │
                                                        ▼
                                        P5-01 ──► P5-02
                                                        │
                                                        ▼
                                        P6-01 ──► P6-02
                                        P6-03 (parallel)
                                                        │
                                                        ▼
                                        P7-01 ──► P7-02
                                                        │
                                                        ▼
                                        P8-01 ──► P8-02
                                                        │
                                                        ▼
                                        P9-01 ──► P9-02
```
