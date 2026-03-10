# Probabilistic Valuation Engine 종합 코드 리뷰 분석

> **주의사항**: 해당 레포지토리(`https://github.com/zbnerd/probabilistic-valuation-engine`)는 비공개(private) 상태로, 실제 소스 코드에 직접 접근할 수 없었습니다. 본 분석은 **요청에 명시된 아키텍처 명세**(멀티 모듈 구조, CQRS/Event Sourcing, Hexagonal Architecture, Redis Streams, MySQL+MongoDB, Spring Batch, JWT 인증)를 기반으로, 해당 기술 스택에서 **반드시 확인해야 할 이슈**를 체계적으로 정리한 것입니다. 실제 코드 확인 후 각 항목의 해당 여부를 점검하시기 바랍니다.

---

## P0 (Critical): 운영 장애·데이터 유실·보안 취약점

### 1. MySQL → Redis Streams 이중 쓰기(Dual-Write) 데이터 정합성 붕괴

**영향 범위**: Command Handler 전체 (module-app 또는 module-core 내 유스케이스 클래스)

**문제**: MySQL에 JPA 트랜잭션으로 커맨드를 저장한 뒤 Redis Streams에 `XADD`로 이벤트를 발행하는 구조에서, 두 시스템 간 **원자적 트랜잭션이 불가능**하다. Spring의 `@Transactional`은 `JpaTransactionManager`만 관리하며, Redis 연산은 그 범위 밖이다.

**장애 시나리오**:
- MySQL 커밋 성공 → Redis `XADD` 전 서버 크래시 → **이벤트 유실**, Read Model 영구 불일치
- Redis `XADD` 성공 → MySQL 롤백 → **팬텀 이벤트** 발행, 존재하지 않는 데이터 기반 Read Model 생성
- `@TransactionalEventListener(phase = AFTER_COMMIT)` 사용 시에도 커밋 후~리스너 실행 전 크래시 윈도우 존재

**심각성**: 이벤트 유실은 CQRS 시스템에서 **Read Model의 영구적 드리프트**를 야기하며, 자동 복구가 불가능하다.

**권장 수정안**: **Transactional Outbox 패턴** 도입. MySQL 내 `outbox` 테이블에 이벤트를 동일 트랜잭션으로 저장하고, 별도 Relay 프로세스가 폴링하여 Redis Streams로 발행. Debezium CDC로 MySQL binlog을 직접 읽는 방식도 대안.

```kotlin
// 수정 전 (위험)
@Transactional
fun placeOrder(cmd: PlaceOrderCommand) {
    val order = orderRepository.save(Order.create(cmd))
    redisTemplate.opsForStream().add("order-events", mapOf("type" to "OrderPlaced", "id" to order.id))
}

// 수정 후 (Outbox 패턴)
@Transactional
fun placeOrder(cmd: PlaceOrderCommand) {
    val order = orderRepository.save(Order.create(cmd))
    outboxRepository.save(OutboxEvent("OrderPlaced", objectMapper.writeValueAsString(order)))
    // Redis 발행은 별도 Relay 프로세스가 담당
}
```

---

### 2. Redis Streams 소비자 장애 시 메시지 좀비화 및 무한 크래시 루프

**영향 범위**: module-infra 내 Redis Streams Consumer 구현체

**문제 2-1: PEL(Pending Entries List) 좀비 메시지**
소비자가 `XREADGROUP`으로 메시지를 수신한 뒤 처리 중 크래시하면, 해당 메시지는 PEL에 남지만 재전달되지 않는다. 재시작 시 `>` ID만 사용하여 새 메시지만 읽으면, **PEL 내 메시지는 영구히 처리되지 않는다**.

**올바른 패턴**: 2단계 시작 — (1) ID `0`으로 PEL 내 미처리 메시지 먼저 소비, (2) 모두 ACK 후 `>`로 전환.

**문제 2-2: Poison Pill 무한 크래시 루프**
비정상 메시지가 소비자를 반복 크래시시킴 → 다른 소비자가 `XAUTOCLAIM`으로 해당 메시지를 가져감 → 동일 크래시 → 전체 소비자 그룹 다운.

**권장 수정안**: `XPENDING`의 `times-delivered` 카운터를 확인하여, **N회 초과 재시도된 메시지는 Dead Letter Queue(DLQ) 스트림으로 이동**하고 원본을 `XACK` 처리.

**문제 2-3: 소비자 자동 리밸런싱 부재**
Redis Streams는 Kafka와 달리 **자동 리밸런싱이 없다**. 소비자가 영구 종료되면 할당된 메시지가 PEL에 고아 상태로 남는다. 모든 소비자에 `XAUTOCLAIM` 기반 주기적 청소 로직(Janitor) 구현 필수.

---

### 3. JWT 인증 알고리즘 혼동(Algorithm Confusion) 공격

**영향 범위**: module-web 또는 module-infra 내 JWT 인증 필터/검증 로직

**문제**: JWT 토큰의 `alg` 헤더를 서버가 신뢰하는 구조에서, 공격자가 `RS256`을 `HS256`으로 변경하고 서버의 **공개키(public key)를 HMAC 시크릿으로 사용**하여 서명하면, 서버가 이를 유효한 토큰으로 수락한다. 2026년 Q1 CVE 클러스터(CVE-2026-22817, CVE-2026-27804, CVE-2026-23552)로 재점화된 이슈.

**추가 공격 벡터**:
- `alg: none` 변형(`nOnE`, `NONE`) — 서명 없이 토큰 수락
- `jwk` 헤더 인젝션 — 공격자가 자신의 공개키를 토큰에 삽입
- `kid` 파라미터 경로 탐색/SQL 인젝션

**권장 수정안**: 서버 측에서 **허용 알고리즘을 하드코딩**하고, 토큰의 `alg` 헤더를 무시. Nimbus JOSE+JWT 라이브러리는 unsigned/HMAC/signature JWT를 별도 타입으로 분리하여 혼동을 방지.

```kotlin
// 반드시 서버 측에서 알고리즘 강제
val parser = Jwts.parserBuilder()
    .setSigningKey(secretKey)
    .require("alg", "HS256") // 명시적 알고리즘 화이트리스트
    .build()
```

---

### 4. application.yml 시크릿 평문 노출

**영향 범위**: 전체 모듈의 `application.yml`, `application-{profile}.yml`

**문제**: JWT 시크릿, DB 비밀번호, Redis 비밀번호, 외부 API 키가 **Git에 커밋된 평문 설정 파일**에 존재할 가능성. Spring Boot Actuator가 기본 활성화된 경우 `/actuator/env` 엔드포인트를 통해 환경 변수가 노출될 수 있다.

**권장 수정안**: 환경 변수(`${JWT_SECRET}`), Spring Cloud Config Server + 암호화, HashiCorp Vault, K8s Secrets 활용. Actuator 엔드포인트 접근 제어 필수.

---

### 5. 이벤트 핸들러 비멱등성(Non-Idempotent) 처리로 인한 데이터 손상

**영향 범위**: module-infra 또는 module-app 내 이벤트 프로젝션 핸들러

**문제**: Outbox 패턴과 Redis Streams Consumer Group 모두 **at-least-once 전달**을 보장한다. 즉, 동일 이벤트가 **중복 수신**될 수 있다. 이벤트 핸들러가 `counter++` 같은 비멱등 연산을 수행하면, 중복 처리 시 **데이터가 부풀려지거나 손상**된다.

**권장 수정안**:
- 모든 프로젝션에 **절대값 SET 연산** 사용 (INCREMENT 대신)
- MongoDB `upsert` 사용 (INSERT 대신)
- 처리된 이벤트 ID를 TTL과 함께 추적하여 중복 스킵

---

### 6. JPQL/Native Query 인젝션 취약점

**영향 범위**: module-infra 내 Repository 구현체, `@Query` 어노테이션 사용부

**문제**: JPQL에서 **문자열 연결(concatenation)**으로 사용자 입력을 쿼리에 삽입하면 SQL 인젝션이 가능하다. JPA가 자동으로 방어해주지 않는다. `nativeQuery = true`인 `@Query`에서도 동일.

```java
// 취약한 코드
entityManager.createQuery("select v from Valuation v where v.ticker = '" + ticker + "'")

// 안전한 코드
entityManager.createQuery("select v from Valuation v where v.ticker = :ticker")
    .setParameter("ticker", ticker)
```

**MongoDB NoSQL 인젝션**: `MongoTemplate`에서 사용자 입력을 `$where` 절이나 JSON 문자열 생성에 사용하면 `$ne`, `$gt` 등 연산자 인젝션이 가능. Spring Data MongoDB의 타입 안전한 `Criteria` API 사용 필수.

---

## P1 (High): 아키텍처 위반·성능 병목·주요 기술 부채

### 7. module-core에 JPA 어노테이션이 존재하면 Hexagonal Architecture 위반

**영향 범위**: module-core 내 도메인 엔티티 클래스

**문제**: 도메인 객체에 `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue` 등 JPA 어노테이션이 직접 부착되면, **도메인 계층이 인프라 기술에 종속**된다. 이는 Hexagonal Architecture의 핵심 원칙("모든 소스 코드 의존성은 안쪽을 향해야 한다")을 정면 위반한다.

**추가 위반 패턴들**:
- module-core에 `@Service`, `@Component`, `@Transactional` 등 Spring 어노테이션 존재
- module-core에 `@JsonProperty` 등 Jackson 어노테이션 존재
- module-core의 `build.gradle.kts`에 `spring-boot-starter-data-jpa` 의존성 선언

**올바른 의존 방향**:
```
module-app  → module-core, module-infra, module-web
module-web  → module-core (포트/인터페이스만)
module-infra → module-core (포트 구현)
module-core → module-common (또는 아무것도 아님)
module-common → 없음
```

**권장 수정안**: module-infra에 별도 JPA 엔티티 생성 + Mapper를 통해 도메인 객체와 변환. module-core는 **프레임워크 의존성 zero** 유지. ArchUnit으로 아키텍처 규칙을 테스트에서 자동 검증.

---

### 8. Kotlin data class를 JPA 엔티티로 사용하는 치명적 안티패턴

**영향 범위**: module-infra(또는 module-core) 내 `@Entity` 클래스

**문제**: Kotlin `data class`는 JPA 엔티티로 **사용해서는 안 된다**. JetBrains 공식 블로그(2026년 1월)에서도 명확히 경고한 사항:

| data class 특성 | JPA 요구사항 | 충돌 |
|---|---|---|
| 기본 final 클래스 | open 필수 (프록시 서브클래싱) | ❌ Lazy loading 불가 |
| All-args 생성자만 | No-arg 생성자 필수 | ❌ 리플렉션 인스턴스 생성 실패 |
| `val` (불변) 기본 | `var` (가변) 필수 | ❌ Hibernate 상태 변경 불가 |
| 전체 프로퍼티 기반 equals/hashCode | ID 기반만 사용 | ❌ 영속화 전후 hashCode 변경 |
| 전체 프로퍼티 toString | Lazy 컬렉션 포함 시 | ❌ N+1 쿼리 또는 LazyInitializationException |

**핵심 버그**: `hashCode`가 모든 프로퍼티 기반이므로, `repository.save()` 후 `@Id`가 할당되면 **HashSet에 저장된 엔티티를 찾을 수 없게** 된다.

**권장 수정안**: `open class` 사용, ID 기반 `equals`/`hashCode` 수동 구현, `kotlin-jpa` 플러그인 + `allOpen` 블록 반드시 설정.

---

### 9. Spring Batch와 실시간 이벤트 프로젝션 간 Race Condition

**영향 범위**: module-app 또는 module-infra 내 Spring Batch Job + Redis Streams Event Consumer

**문제**: Spring Batch로 Read Model을 주기적으로 재구축하면서, 동시에 실시간 이벤트 컨슈머가 동일 MongoDB Read Model을 업데이트하면, **배치가 이전 상태로 실시간 업데이트를 덮어쓸** 수 있다.

**시나리오**: 배치 시작(T1) → 실시간 이벤트로 문서 업데이트(T2) → 배치가 T1 시점 데이터로 같은 문서 덮어쓰기(T3) → **최신 데이터 유실**

**권장 수정안**: Read Model 문서에 버전 번호 또는 타임스탬프 포함. 조건부 업데이트(`version`이 현재보다 클 때만 갱신). 배치 재구축과 실시간 프로젝션의 대상 컬렉션을 명확히 분리.

---

### 10. MongoDB 이중 지연(Double Eventual Consistency) 문제

**영향 범위**: 전체 쿼리 경로 (module-web → MongoDB Read Replica)

**문제**: 이 아키텍처에는 **두 단계의 eventual consistency**가 존재:
1. MySQL 커밋 → Redis Streams 발행 → 이벤트 소비 → MongoDB Primary 쓰기 (이벤트 전파 지연)
2. MongoDB Primary → Secondary 복제 지연

사용자가 체감하는 **총 지연 = 두 지연의 합산**. Read Replica에서 읽는 경우, "내가 방금 쓴 데이터가 안 보이는" **Read-Your-Writes 위반**이 발생한다.

**권장 수정안**: 
- 커맨드 직후 해당 사용자의 쿼리는 `readPreference: primary`로 라우팅
- MongoDB Causal Consistency Sessions 활용 (3.6+)
- Read Concern `"majority"` 설정으로 롤백 가능 데이터 조회 방지

---

### 11. 멀티 DataSource 환경에서 잘못된 TransactionManager 바인딩

**영향 범위**: module-app 또는 module-infra의 `@Configuration` 클래스, `@Transactional` 사용부 전체

**문제**: MySQL(Command)과 MongoDB(Query)를 함께 사용할 때, Spring Boot의 자동 설정은 **단일 DataSource를 가정**한다. `@Transactional` 어노테이션이 의도하지 않은 TransactionManager에 바인딩될 수 있다. MongoDB 작업에 JPA TransactionManager가 적용되거나 그 반대.

**권장 수정안**: `@Transactional("mysqlTransactionManager")` 처럼 **명시적으로 TransactionManager 지정**. 각 DataSource별 `EntityManagerFactory`, `TransactionManager`를 수동 설정.

---

### 12. N+1 쿼리 문제

**영향 범위**: module-infra 내 Spring Data JPA Repository 사용부 전체

**문제**: N개 엔티티 조회 후 각각의 Lazy-loaded 연관관계에 접근하면 **N+1개 쿼리**가 실행된다. 개발 환경의 소량 데이터에서는 감지되지 않다가 운영 환경에서 **성능 장애**로 나타난다.

**특히 위험한 패턴**: Hexagonal Architecture에서 도메인 서비스가 엔티티의 연관 객체에 접근할 때, 트랜잭션 경계 밖에서 Lazy Loading이 트리거되면 `LazyInitializationException` 발생.

**권장 수정안**:
- JPQL `JOIN FETCH` 또는 `@EntityGraph` 사용
- `spring.jpa.properties.hibernate.default_batch_fetch_size=20` 글로벌 배치 사이즈 설정
- DTO Projection으로 엔티티 로딩 자체를 회피
- `spring.jpa.open-in-view=false` 설정 (기본값 `true`는 성능 안티패턴)

---

### 13. Redis Cache Stampede (Thundering Herd)

**영향 범위**: module-infra 내 캐시 계층

**문제**: 인기 캐시 키가 만료되는 순간, 수천 개의 동시 요청이 **동시에 DB를 직접 조회**한다. CQRS Read Model 위에 별도 Redis 캐시를 얹은 구조에서, 프로젝션 지연 + 캐시 만료가 겹치면 **DB 과부하 → 장애**로 이어진다.

**권장 수정안**: 
- Mutex 기반 락 (`SETNX`로 분산 락)
- X-Fetch 알고리즘(확률적 조기 만료)
- TTL에 랜덤 지터 추가 (Cache Avalanche 방지)
- 로컬 Caffeine 캐시를 Redis 앞에 배치 (Hot Key 대응)

---

### 14. HikariCP 커넥션 풀과 Tomcat 스레드 풀 불일치

**영향 범위**: application.yml의 `spring.datasource.hikari.*` 및 `server.tomcat.*` 설정

**문제**: Tomcat 기본 최대 스레드 **200개**인데, HikariCP 기본 최대 커넥션이 **10개**이면, 190개 스레드가 DB 커넥션을 기다리며 블로킹된다. 

**최적 커넥션 수 공식**: `connections = (CPU 코어 × 2) + SSD 수` (4코어 SSD 1대 = **9개**)

**권장 수정안**:
```yaml
spring.datasource.hikari:
  maximum-pool-size: 15       # 서버 스펙에 맞게 조정
  minimum-idle: 5
  connection-timeout: 10000   # fail-fast
  leak-detection-threshold: 60000  # 커넥션 누수 탐지
  max-lifetime: 1680000       # DB timeout보다 짧게
```

---

### 15. 이벤트 순서 보장 부재

**영향 범위**: module-infra 내 Redis Streams Consumer Group

**문제**: Redis Streams Consumer Group에서 메시지가 **여러 소비자에게 분배**되면, 동일 Aggregate에 대한 이벤트 순서가 보장되지 않는다. `OrderCreated(v1)` 보다 `ItemAdded(v2)`가 먼저 처리되면 **Read Model 손상**.

**권장 수정안**: 각 이벤트에 **모노토닉 버전 번호** 포함. 프로젝션에 `lastAppliedVersion` 저장. `version > lastAppliedVersion + 1`이면 버퍼링 후 순서대로 적용.

---

### 16. Spring Security CORS 설정 오류

**영향 범위**: module-web 내 Security Configuration

**문제**: CORS가 Spring Security보다 **먼저** 처리되어야 한다. Pre-flight OPTIONS 요청에는 인증 헤더가 없으므로, Security가 먼저 처리하면 **401로 거부**된다. 또한 `allowedOrigins("*")` + `allowCredentials(true)`는 CORS 스펙 위반.

**권장 수정안**: `SecurityFilterChain`에 `.cors { it.configurationSource(corsConfigurationSource()) }` 명시적 설정. 명시적 오리진 지정.

---

## P2 (Medium): 코드 품질·문서화·테스트 커버리지

### 17. Gradle 멀티 모듈 빌드 설정 이슈

**영향 범위**: 각 모듈의 `build.gradle.kts`

**점검 항목**:
- `api` vs `implementation` 오용: module-infra가 `api(project(":module-core"))`로 선언하면 module-core의 내부 타입이 의존 모듈에 누출
- module-core에 `spring-boot-starter-*` 의존성이 선언되어 있으면 아키텍처 위반
- `kotlin-jpa` 플러그인만으로는 `allOpen`이 자동 설정되지 않음 — `allOpen { annotation("jakarta.persistence.Entity") }` 블록 별도 추가 필수
- 버전 카탈로그(`libs.versions.toml`) 미사용 시 의존성 버전 불일치 위험
- `bootJar` 태스크는 module-app에만 적용되어야 하며, 라이브러리 모듈에는 `jar` 태스크만

---

### 18. 컴포넌트 스캐닝 및 엔티티 스캐닝 문제

**영향 범위**: module-app의 메인 애플리케이션 클래스

**문제**: `@SpringBootApplication`은 **자신의 패키지와 하위 패키지만 스캔**한다. 멀티 모듈에서 각 모듈이 별도 루트 패키지를 사용하면, 다른 모듈의 Bean이 등록되지 않는다.

**증상**: `Parameter 0 of constructor required a bean of type '...' that could not be found.`

**권장 수정안**:
```kotlin
@SpringBootApplication(scanBasePackages = ["com.example"])
@EntityScan(basePackages = ["com.example.infra.entity"])
@EnableJpaRepositories(basePackages = ["com.example.infra.repository"])
class Application
```

---

### 19. Spring Batch Job 재시작 실패

**영향 범위**: module-app 내 Spring Batch Job 설정

**문제**: 프로세스가 강제 종료(`kill -9`)되면, `JobRepository`에 상태가 `STARTED`로 남아 **재실행이 차단**된다. `COMPLETED` 상태의 Job Instance는 재시작 불가 — 매 실행마다 고유한 Job Parameter(예: 타임스탬프) 필요.

**권장 수정안**: 유니크 Job Parameter 사용, 비정상 종료 감지 후 `FAILED`/`ABANDONED`로 상태 전이하는 복구 로직 구현.

---

### 20. 이벤트 스키마 진화(Schema Evolution) 미대응

**영향 범위**: Redis Streams에 저장된 이벤트, 이벤트 역직렬화 코드 전체

**문제**: 애플리케이션이 진화하면서 이벤트 구조가 변경되면, Redis Streams에 남아있는 **과거 이벤트가 역직렬화에 실패**한다.

**권장 수정안**: 이벤트 페이로드에 버전 필드 포함. 이전 버전 이벤트를 현재 형식으로 변환하는 **Upcaster** 구현.

---

### 21. REST Controller 입력 검증 누락

**영향 범위**: module-web 내 `@RestController` 클래스

**문제**: `@RequestBody`에 `@Valid` 어노테이션이 누락되면 DTO의 Bean Validation(`@NotBlank`, `@Size` 등)이 **작동하지 않는다**. `@PathVariable`에 대한 검증도 기본적으로 비활성화.

**권장 수정안**: 모든 `@RequestBody`에 `@Valid` 추가, 컨트롤러 클래스에 `@Validated` 추가, Path Variable에 `@Pattern` 제약 조건 적용.

---

### 22. Rate Limiting 부재 또는 불완전

**영향 범위**: module-web 전체 API 엔드포인트

**문제**: Spring Boot 3.x에는 **빌트인 Rate Limiting이 없다**. In-memory 구현은 다중 인스턴스 환경에서 무효하며, 인증 엔드포인트에 Rate Limit이 없으면 Brute Force 공격에 노출.

**권장 수정안**: Bucket4j + Redis 기반 분산 Rate Limiting, 또는 API Gateway 레벨에서 처리.

---

### 23. 테스트 커버리지 갭

**영향 범위**: 전체 테스트 코드

**주요 누락 가능 영역**:
- **이벤트 핸들러(프로젝션) 단위 테스트**: 커맨드 핸들러만 테스트하고 Read Model 프로젝션은 미테스트
- **Command → Event → Projection 통합 테스트**: 전체 파이프라인 검증 부재
- **Eventual Consistency 테스트**: `Thread.sleep()` 대신 `Awaitility` 미사용으로 인한 Flaky Test
- **H2 대신 실제 DB 테스트**: H2와 MySQL/MongoDB 간 동작 차이로 운영 환경 장애 미발견
- **이벤트 스키마 진화 테스트**: Upcaster가 과거 이벤트를 올바르게 변환하는지 검증 부재
- **Aggregate 상태 복원 테스트**: 이벤트 리플레이로 정확한 상태가 복원되는지 미검증

**권장 수정안**:
- Testcontainers로 MySQL, MongoDB, Redis 실제 인스턴스 사용
- `@RecordApplicationEvents`로 이벤트 발행 검증
- 싱글톤 컨테이너 패턴 사용 시 `static` 블록에서 시작 (JUnit 어노테이션 방식 회피)

---

### 24. open-in-view 기본값 문제

**영향 범위**: application.yml의 JPA 설정

**문제**: `spring.jpa.open-in-view`의 기본값이 `true`로, View 렌더링/JSON 직렬화 중에도 Hibernate 세션이 열려있어 **컨트롤러 레이어에서 DB 쿼리가 실행**된다. 개발 시 편리하지만 운영 환경에서 예측 불가능한 성능 문제 유발.

**권장 수정안**: `spring.jpa.open-in-view=false` 명시 설정. 필요한 데이터는 Service 계층에서 미리 로딩.

---

### 25. @Async 기본 TaskExecutor 미설정

**영향 범위**: `@Async` 어노테이션 사용부 전체

**문제**: 기본 `SimpleAsyncTaskExecutor`는 **호출마다 새 스레드를 생성**하여, 풀링이 없고 스레드 수 제한도 없다. 부하 시 **메모리 고갈** 위험.

**권장 수정안**: 명시적 `ThreadPoolTaskExecutor` Bean 설정 — `corePoolSize`, `maxPoolSize`, `queueCapacity` 지정.

---

### 26. 문서화 점검 필요 항목

**영향 범위**: README.md, ADR 문서, 아키텍처 다이어그램

**확인 필요**:
- README의 아키텍처 설명과 **실제 구현 간 괴리**: 예를 들어 README에서 Hexagonal Architecture를 표방하면서 실제로는 module-core에 JPA 의존성이 있는 경우
- Outbox 패턴 도입 여부가 ADR에 기록되어 있는지
- 이벤트 스키마 버전 관리 전략 문서화
- MongoDB Read Preference 및 Consistency 전략 문서화
- Batch Job과 실시간 프로젝션의 충돌 해결 전략 문서화

---

## 종합 체크리스트 요약

| 우선순위 | 건수 | 핵심 키워드 |
|:---:|:---:|---|
| **P0** | 6건 | Dual-Write 정합성, Redis PEL 좀비, JWT 알고리즘 혼동, 시크릿 노출, 비멱등 이벤트 처리, 인젝션 |
| **P1** | 10건 | Hexagonal 위반, data class JPA, Batch-Realtime Race Condition, 이중 지연, TransactionManager 오바인딩, N+1, Cache Stampede, 커넥션 풀 불일치, 이벤트 순서, CORS |
| **P2** | 10건 | Gradle 설정, 컴포넌트 스캐닝, Batch 재시작, 스키마 진화, 입력 검증, Rate Limiting, 테스트 갭, OSIV, @Async, 문서화 |

**다음 단계**: 레포지토리 접근 권한이 확보되면, 위 26개 항목에 대해 **실제 소스 코드 라인 레벨**로 구체적인 파일/메서드/라인 번호를 식별하여 보완 분석이 가능합니다. 특히 P0 항목들은 코드 접근 후 **즉시 확인**이 필요합니다.