---
id: GR-ARCH-010
category: architecture
severity: critical
keywords: [V2, V4, Facade, Decorator, ServiceModule, Dependency]
---

# Service Modules Architecture Guardrails

## Overview

probabilistic-valuation-engine 서비스 레이어는 **V2 (핵심 비즈니스)**와 **V4 (성능 강화)** 두 세대로 구성됩니다. 모듈 간 의존성 방향과 설계 패턴을 엄격히 준수해야 합니다.

---

## GR-ARCH-010: V2 → V4 의존성 금지

### DON'T (안티패턴)

```java
// 안티패턴: V4 모듈 내부에서 V2 직접 호출
@Service
public class EquipmentExpectationServiceV4 {
    private final EquipmentService v2Service;  // ❌ V2 의존성

    public ExpectationResponse calculate(String ocid) {
        return v2Service.calculate(ocid);  // V4 최적화 우회
    }
}
```

### DO (베스트 프랙티스)

```java
// Good: V4 독립 구현 또는 Facade 통해 간접 호출
@Service
public class EquipmentExpectationServiceV4 {
    private final ExpectationCacheCoordinator cache;  // ✅ V4 전용
    private final EquipmentExpectationCalculator calculator;  // ✅ V4 전용
}
```

**핵심 규칙:**
- V4는 V2의 인터페이스만 의존하거나 독립 구현
- V4의 성능 최적화(719 RPS)가 무의미해지지 않도록 격리
- V2 → V4 호출은 허용 (점진적 마이그레이션)

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Anti-Patterns to Avoid

---

## GR-ARCH-011: Facade 패턴 필수

### DON'T (안티패턴)

```java
// 안티패턴: Controller가 여러 서비스 직접 호출
@RestController
public class CharacterController {
    private final GameCharacterService characterService;
    private final EquipmentService equipmentService;
    private final LikeSyncService likeService;
    private final DonationService donationService;
    // 책임이 Controller에 집중됨

    @GetMapping("/characters/{ign}")
    public ResponseEntity<?> getCharacter(@PathVariable String ign) {
        // 복잡한 오케스트레이션 로직이 Controller에 노출
        Character character = characterService.findByIgn(ign);
        Equipment equipment = equipmentService.getEquipment(character.getOcid());
        LikeCount likes = likeService.getLikes(character.getOcid());
        // ...
    }
}
```

### DO (베스트 프랙티스)

```java
// Good: Facade로 복잡성 은폐
@Service
public class GameCharacterFacade {
    private final GameCharacterService characterService;
    private final EquipmentService equipmentService;
    private final LikeSyncService likeService;

    public CharacterResponse getCharacterSummary(String ign) {
        // 복잡한 오케스트레이션을 Facade에 캡슐화
        return CharacterResponse.builder()
            .character(characterService.findByIgn(ign))
            .equipment(equipmentService.getEquipment(ocid))
            .likes(likeService.getLikes(ocid))
            .build();
    }
}

@RestController
public class CharacterController {
    private final GameCharacterFacade facade;  // 단일 의존성

    @GetMapping("/characters/{ign}")
    public ResponseEntity<?> getCharacter(@PathVariable String ign) {
        return ResponseEntity.ok(facade.getCharacterSummary(ign));
    }
}
```

**핵심 규칙:**
- Controller는 Facade만 의존 (단일 책임)
- 복잡한 오케스트레이션은 Facade에 캡슐화
- Facade는 여러 서비스를 조합하지만, 각 서비스는 SRP 준수
- V4 메인 Facade: `EquipmentExpectationServiceV4`

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Section 8: facade (통합 진입점)

---

## GR-ARCH-012: Decorator Chain 패턴 필수

### DON'T (안티패턴)

```java
// 안티패턴 1: 거대한 if-else 체인
public BigDecimal calculateCost(Equipment equipment) {
    BigDecimal total = BigDecimal.ZERO;

    if (equipment.hasBlackCube()) {
        total = total.add(calculateBlackCubeCost(equipment));
    }
    if (equipment.hasAdditionalCube()) {
        total = total.add(calculateAdditionalCubeCost(equipment));
    }
    if (equipment.hasStarforce()) {
        total = total.add(calculateStarforceCost(equipment));
    }
    // 확장이 어려움

    return total;
}

// 안티패턴 2: 상속 깊이 증가
public class BlackCubeWithStarforceItem extends BlackCubeItem {
    @Override
    public BigDecimal getCost() {
        return super.getCost().add(starforceCost);
    }
}
// 조합이 불가능하고 클래스 폭발
```

### DO (베스트 프랙티스)

```java
// Good: Decorator Chain으로 조합 가능한 계산
public interface EquipmentExpectationCalculator {
    CostBreakdown calculate(Equipment equipment);
}

public abstract class EquipmentEnhanceDecorator implements EquipmentExpectationCalculator {
    protected final EquipmentExpectationCalculator delegate;

    protected EquipmentEnhanceDecorator(EquipmentExpectationCalculator delegate) {
        this.delegate = delegate;
    }

    @Override
    public CostBreakdown calculate(Equipment equipment) {
        return delegate.calculate(equipment);  // 위임
    }
}

public class BaseEquipmentItem implements EquipmentExpectationCalculator {
    @Override
    public CostBreakdown calculate(Equipment equipment) {
        return CostBreakdown.of(BigDecimal.ZERO, Map.of());  // 시작점
    }
}

public class BlackCubeDecoratorV4 extends EquipmentEnhanceDecorator {
    @Override
    public CostBreakdown calculate(Equipment equipment) {
        CostBreakdown base = delegate.calculate(equipment);
        if (equipment.hasBlackCube()) {
            BigDecimal cubeCost = calculateBlackCubeCost(equipment);
            return base.add(cubeCost, CostType.BLACK_CUBE);
        }
        return base;
    }
}

// Factory로 동적 조합
public class EquipmentExpectationCalculatorFactory {
    public EquipmentExpectationCalculator create(Equipment equipment) {
        EquipmentExpectationCalculator calculator = new BaseEquipmentItem();

        if (equipment.hasBlackCube()) {
            calculator = new BlackCubeDecoratorV4(calculator);
        }
        if (equipment.hasAdditionalPotential()) {
            calculator = new AdditionalCubeDecoratorV4(calculator);
        }
        if (equipment.getStarforce() > 0) {
            calculator = new StarforceDecoratorV4(calculator);
        }

        return calculator;
    }
}
```

**핵심 규칙:**
- Decorator: 장비 강화 비용 누적 계산
- Factory: 조건부 Decorator 체인 조합
- BigDecimal: 오차 없는 정밀 계산
- OCP: 새로운 강화 종류 추가 시 기존 코드 수정 없이 새 Decorator 추가

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Section 5: calculator, Section 7: calculator/v4

---

## GR-ARCH-013: Strategy 패턴 필수

### DON'T (안티패턴)

```java
// 안티패턴: if-else로 구현 전략 분기
public enum BufferType {
    IN_MEMORY, REDIS
}

public void flushBuffer() {
    if (type == BufferType.IN_MEMORY) {
        inMemoryBuffer.clear();
    } else if (type == BufferType.REDIS) {
        redisTemplate.delete(keys);
    }
    // 새로운 구현 추가 시 분기문 증가
}
```

### DO (베스트 프랙티스)

```java
// Good: Strategy 패턴으로 알고리즘 교체 가능
public interface LikeBufferStrategy {
    FetchResult fetchAndClear(String characterId);
}

public class InMemoryLikeBufferStrategy implements LikeBufferStrategy {
    private final LikeRelationBuffer buffer;

    @Override
    public FetchResult fetchAndClear(String characterId) {
        return buffer.fetchAndClear(characterId);
    }
}

public class RedisLikeBufferStrategy implements LikeBufferStrategy {
    private final RedisTemplate<String, String> template;

    @Override
    public FetchResult fetchAndClear(String characterId) {
        // Lua Script로 원자적 fetch
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_FETCH_SCRIPT, Long.class);
        Long result = template.execute(script, keys(characterId));
        return FetchResult.from(result);
    }
}

// 실행 시점에 구현체 주입
@Service
public class LikeSyncService {
    private final LikeBufferStrategy bufferStrategy;  // @Qualifier로 선택

    public void syncLike(String characterId) {
        FetchResult result = bufferStrategy.fetchAndClear(characterId);
        // ...
    }
}
```

**핵심 규칙:**
- 인터페이스: 알고리즘의 골격 정의
- 구현체: 각각의 구체적인 알고리즘 구현
- OCP 준수: 새로운 전략 추가 시 기존 코드 수정 없음
- 적용 위치: LikeBuffer, AtomicFetch, BackoffStrategy, PaymentStrategy

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Section 4: cache, Section 10: like

---

## GR-ARCH-014: Transactional Outbox 패턴 필수

### DON'T (안티패턴)

```java
// 안티패턴 1: 트랜잭션 후 메시지 전송 (비원자성)
@Transactional
public void processDonation(DonationRequest request) {
    donationRepository.save(donation);
}
// ❌ 여기서 장애 발생 시 메시지 유실 가능
@EventListener
public void publishDonationEvent(DonationSavedEvent event) {
    kafkaTemplate.send("donation", event);  // 트랜잭션 종료 후 실행
}

// 안티패턴 2: 메시지 큐 없이 DB만 의존 (확장성 부족)
@Transactional
public void processDonation(DonationRequest request) {
    donationRepository.save(donation);
    characterService.updateLikeCount(donation.getCharacterId());  // 동기 호출
    // ❌ 후속 처리 장애 시 donation 롤백 필요
}
```

### DO (베스트 프랙티스)

```java
// Good: Transactional Outbox로 원자성 보장
@Entity
public class DonationOutbox {
    @Id
    private Long id;
    private String aggregateType;  // "Donation"
    private String aggregateId;
    private String eventType;      // "DonationReceived"
    private String payload;        // JSON
    private LocalDateTime createdAt;
    private boolean processed;
}

@Service
public class DonationService {
    private final DonationRepository donationRepository;
    private final OutboxRepository outboxRepository;

    @Transactional
    public void processDonation(DonationRequest request) {
        // 1. 비즈니스 변경
        Donation donation = Donation.from(request);
        donationRepository.save(donation);

        // 2. Outbox에 이벤트 저장 (같은 트랜잭션)
        DonationOutbox outbox = DonationOutbox.builder()
            .aggregateType("Donation")
            .aggregateId(donation.getId().toString())
            .eventType("DonationReceived")
            .payload(JsonUtils.toJson(donation))
            .build();
        outboxRepository.save(outbox);  // ✅ 원자성 보장
    }
}

// 별도 스레드에서 Outbox Polling 및 발행
@Scheduled(fixedDelay = 1000)
public void processOutbox() {
    List<DonationOutbox> events = outboxRepository.findPendingEvents(PageRequest.of(0, 100));

    for (DonationOutbox event : events) {
        try {
            kafkaTemplate.send("donation", event.getPayload());
            outboxRepository.markProcessed(event.getId());
        } catch (Exception e) {
            // 재시도 또는 DLQ 이동
            outboxRepository.incrementRetryCount(event.getId());
        }
    }
}
```

**핵심 규칙:**
- 비즈니스 변경과 Outbox 저장은 같은 DB 트랜잭션
- Outbox Poller는 별도 스레드에서 비동기 실행
- 재시도 메커니즘 + DLQ (Dead Letter Queue)
- 2.1M 이벤트 47분 복구 검증 (N19)

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Section 7: donation

---

## GR-ARCH-015: Write-Behind Buffer 비동기 드레인 필수

### DON'T (안티패턴)

```java
// 안티패턴: 요청 스레드에서 동기 드레인
public void add(ExpectationWriteTask task) {
    buffer.offer(task);
    if (buffer.full()) {
        drain();  // ❌ 요청 스레드 블록
    }
}
```

### DO (베스트 프랙티스)

```java
// Good: 비동기 스케줄러로 백그라운드 드레인
@Service
public class ExpectationPersistenceService {
    private final ExpectationWriteBackBuffer buffer;
    private final ExpectationRepository repository;

    public void persist(ExpectationWriteTask task) {
        boolean offered = buffer.offer(task);
        if (!offered) {
            // Backpressure 시 동기 Fallback
            repository.upsert(task);
        }
    }

    @Scheduled(fixedRate = 100)  // ✅ 별도 스레드에서 실행
    public void drain() {
        List<ExpectationWriteTask> tasks = buffer.drain();
        if (!tasks.isEmpty()) {
            repository.batchUpsert(tasks);
        }
    }
}
```

**핵심 규칙:**
- @Scheduled로 백그라운드 비동기 드레인
- Backpressure 시 동기 Fallback (데이터 유실 방지)
- Lock-free CAS + Exponential Backoff
- Graceful Shutdown 시 3-Phase (Block → Wait → Drain)

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Section 2: buffer

---

## GR-ARCH-016: 모듈 의존성 방향 준수

### DON'T (안티패턴)

```
안티패턴: 순환 의존 또는 잘못된 방향

V4 → V2 직접 호출                    (V4 독립성 훼손)
Controller → Infrastructure 직접 호출  (계층 위반)
Domain → Controller 의존              (역방향 의존)
```

### DO (베스트 프랙티스)

```
의존성 방향 (화살표가 의존하는 방향):

Controller → Facade → Service → Repository → Domain
                ↓
         V4 (성능 계층)
                ↓
         Infrastructure (AOP, Cache, Lock)
```

**핵심 규칙:**
- **상위 계층 → 하위 계층**: 단방향 의존
- **V2 ↔ V4**: V2 → V4 호출 허용, V4 → V2 직접 호출 금지
- **DIP**: 구체적인 구현이 아닌 추상화(인터페이스)에 의존
- **순환 의존 금지**: Gradle dependency report로 검증

### 출처
- [service-modules.md](../../03_Technical_Guides/service-modules.md) - Module Dependency Graph

---

## Verification Commands

```bash
# V4 → V2 직접 호출 방지 검증
grep -r "private.*v2Service" src/main/kotlin/maple/expectation/service/v4/ || echo "✅ No direct V2 calls"

# 동기 드레인 방지 검증
grep -r "drain()" src/main/kotlin/maple/expectation/service/v4/ | grep -v "@Scheduled" || echo "✅ No synchronous drain"

# Decorator 패턴 확인
find src/main/kotlin -name "*Decorator*.java" | head -10

# Strategy 패턴 확인
find src/main/kotlin -name "*Strategy.java" | head -10

# Outbox 테이블 확인
mysql -u root -p -e "SHOW TABLES LIKE '%outbox%';"

# 순환 의존 검증
./gradlew dependencyInsight --dependency maple-core
```

---

## Evidence Links

- [service-modules.md](../../03_Technical_Guides/service-modules.md) - 전체 서비스 모듈 가이드
- [ADR-014](../../01_ADR/ADR-014-multi-module-cross-cutting-concerns.md) - 멀티 모듈 전환 결정
- [WRK Final Summary](../../05_Reports/Portfolio_Enhancement_WRK_Final_Summary.md) - V2 vs V4 성능 비교
