---
id: GR-ARCH-030
category: architecture
severity: critical
keywords: [SOLID, SRP, OCP, LSP, ISP, DIP, DDD, Aggregate, Hexagonal, Rich-Domain]
languages: [java, kotlin]
---

# SOLID Principles & DDD Architecture Guardrails

## Overview

probabilistic-valuation-engine은 **SOLID 원칙**과 **Domain-Driven Design (DDD)**을 엄격히 준수하여 유지보수 가능성과 확장성을 확보합니다. 모든 코드는 이 원칙들을 따라야 합니다.

---

## GR-ARCH-030: SRP (Single Responsibility Principle) - 단일 책임 원칙

### DON'T (안티패턴)

```java
// 안티패턴: 하나의 클래스가 여러 책임을 가짐
@Service
public class GameCharacterService {
    // 책임 1: 캐릭터 조회
    public GameCharacter findCharacter(String ign) { ... }

    // 책임 2: 장비 계산
    public BigDecimal calculateCost(Equipment equipment) { ... }

    // 책임 3: 좋아요 처리
    public void addLike(String characterId) { ... }

    // 책임 4: 알림 발송
    public void sendNotification(String userId, String message) { ... }

    // 책임 5: 캐싱
    public void cacheCharacter(String key, GameCharacter character) { ... }
}
```

**위험성:**
- 변경 이유가 여러 개 → 수정 시 영향 범위 넓음
- 테스트难度 증가 (여러 책임을 한 번에 테스트)
- 재사용 불가능

### DO (베스트 프랙티스)

```java
// Good: 각 클래스는 하나의 책임만 가짐
// 책임 1: 캐릭터 조회
@Service
public class GameCharacterService {
    public GameCharacter findCharacter(String ign) { ... }
}

// 책임 2: 장비 계산
@Service
public class EquipmentCostCalculator {
    public BigDecimal calculateCost(Equipment equipment) { ... }
}

// 책임 3: 좋아요 처리
@Service
public class CharacterLikeService {
    public void addLike(String characterId) { ... }
}

// 책임 4: 알림 발송
@Service
public class NotificationService {
    public void sendNotification(String userId, String message) { ... }
}

// 책임 5: 캐싱
@Component
public class CharacterCache {
    public void cacheCharacter(String key, GameCharacter character) { ... }
}
```

**핵심 규칙:**
- 클래스/메서드는 변경 이유가 하나여야 함
- 메서드는 20라인 이내로 유지
- 책임 분리를 위한 Facade 패턴 적용

### 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID
- CLAUDE.md Section 6: Design Patterns & Structure

---

## GR-ARCH-031: OCP (Open/Closed Principle) - 개방-폐쇄 원칙

### DON'T (안티패턴)

```java
// 안티패턴: 확장을 위해 기존 코드 수정
public enum EquipmentType {
    WEAPON, ARMOR, ACCESSORY
}

public class CostCalculator {
    public BigDecimal calculate(Equipment equipment, EquipmentType type) {
        switch (type) {
            case WEAPON:
                return calculateWeaponCost(equipment);
            case ARMOR:
                return calculateArmorCost(equipment);
            case ACCESSORY:
                return calculateAccessoryCost(equipment);
            // 새로운 타입 추가 시 마다 case 추가 필요
        }
    }
}
```

**위험성:**
- 새로운 장비 타입 추가 시 기존 코드 수정
- switch/if-else 분기문 증가
- 컴파일 후 재배포 필요

### DO (베스트 프랙티스)

```java
// Good: 전략 패턴으로 확장 열림, 수정 닫힘
public interface CostStrategy {
    boolean supports(Equipment equipment);
    BigDecimal calculate(Equipment equipment);
}

public class WeaponCostStrategy implements CostStrategy {
    @Override
    public boolean supports(Equipment equipment) {
        return equipment.getType() == EquipmentType.WEAPON;
    }

    @Override
    public BigDecimal calculate(Equipment equipment) {
        // 무기 비용 계산 로직
    }
}

public class ArmorCostStrategy implements CostStrategy {
    @Override
    public boolean supports(Equipment equipment) {
        return equipment.getType() == EquipmentType.ARMOR;
    }

    @Override
    public BigDecimal calculate(Equipment equipment) {
        // 방어구 비용 계산 로직
    }
}

@Service
public class CostCalculator {
    private final List<CostStrategy> strategies;

    public CostCalculator(List<CostStrategy> strategies) {
        this.strategies = strategies;
    }

    public BigDecimal calculate(Equipment equipment) {
        return strategies.stream()
            .filter(s -> s.supports(equipment))
            .findFirst()
            .map(s -> s.calculate(equipment))
            .orElseThrow(() -> new IllegalArgumentException("Unsupported equipment type"));
    }
}

// 새로운 타입 추가: 새로운 Strategy 클래스만 구현하면 됨
public class PocketItemCostStrategy implements CostStrategy {
    // 구현
}
```

**핵심 규칙:**
- 전략 패턴으로 알고리즘 교체 가능
- 새로운 기능은 새로운 클래스로 추가
- 기존 코드는 수정하지 않음

### 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID
- docs/16_Guardrails/architecture/service-modules.md - Strategy 패턴

---

## GR-ARCH-032: LSP (Liskov Substitution Principle) - 리스코프 치환 원칙

### DON'T (안티패턴)

```java
// 안티패턴: 하위 타입에서 상위 타입의 계약을 위반
public interface EquipmentEnhanceDecorator {
    // 기본 계산 + 추가 비용
    CostBreakdown calculate(Equipment equipment);
}

public class StarforceDecorator implements EquipmentEnhanceDecorator {
    @Override
    public CostBreakdown calculate(Equipment equipment) {
        // ❌ 조건에 따라 null 반환 (계약 위반)
        if (equipment.getStarforce() == 0) {
            return null;  // 상위 타입은 항상 CostBreakdown 반환 예상
        }
        return delegate.calculate(equipment).add(starforceCost);
    }
}
```

**위험성:**
- null 체크 강제 → 호출자가 예외 처리 필요
- 다형성 사용 불가
- 런타임 NPE 발생

### DO (베스트 프랙티스)

```java
// Good: 상위 타입의 계약을 준수
public interface EquipmentEnhanceDecorator {
    // 항상 유효한 CostBreakdown 반환
    CostBreakdown calculate(Equipment equipment);
}

public class StarforceDecorator implements EquipmentEnhanceDecorator {
    @Override
    public CostBreakdown calculate(Equipment equipment) {
        // ✅ 조건 없이 항상 계산 (0일 경우 빈 값 추가)
        BigDecimal cost = equipment.getStarforce() > 0
            ? calculateStarforceCost(equipment)
            : BigDecimal.ZERO;
        return delegate.calculate(equipment).add(cost, CostType.STARFORCE);
    }
}
```

**핵심 규칙:**
- 하위 타입은 상위 타입의 계약을 준수
- null 반환 금지 (Empty 객체 또는 기본값)
- 사전 조건 강화 금지, 사후 조건 약화 금지

### 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID

---

## GR-ARCH-033: ISP (Interface Segregation Principle) - 인터페이스 분리 원칙

### DON'T (안티패턴)

```java
// 안티패턴: 거대한 인터페이스 (불필요한 메서드 강제)
public interface EquipmentRepository {
    // 조회
    Optional<Equipment> findById(String id);
    List<Equipment> findAll();

    // 저장
    Equipment save(Equipment equipment);

    // 삭제
    void deleteById(String id);

    // 캐싱 (불필요)
    void cache(String key, Equipment equipment);
    Optional<Equipment> fromCache(String key);

    // 통계 (불필요)
    long countByType(EquipmentType type);
    Map<EquipmentType, Long> statistics();
}

// 단순 조회만 필요한 서비스도 모든 메서드를 구현해야 함
@Service
public class EquipmentQueryService {
    private final EquipmentRepository repository;
    // 캐싱, 통계 메서드는 사용하지 않음
}
```

### DO (베스트 프랙티스)

```java
// Good: 인터페이스 분리
// 조회용 인터페이스
public interface EquipmentQueryRepository {
    Optional<Equipment> findById(String id);
    List<Equipment> findAll();
}

// 저장용 인터페이스
public interface EquipmentCommandRepository {
    Equipment save(Equipment equipment);
    void deleteById(String id);
}

// 캐싱용 인터페이스
public interface EquipmentCache {
    void cache(String key, Equipment equipment);
    Optional<Equipment> fromCache(String key);
}

// 통계용 인터페이스
public interface EquipmentStatistics {
    long countByType(EquipmentType type);
    Map<EquipmentType, Long> statistics();
}

// 필요한 인터페이스만 의존
@Service
@RequiredArgsConstructor
public class EquipmentQueryService {
    private final EquipmentQueryRepository repository;  // 조회만 사용
}
```

**핵심 규칙:**
- 클라이언트가 사용하지 않는 인터페이스에 의존하지 않음
- 인터페이스를 목적별로 세분화
- CQRS 패턴으로 Command/Query 분리

### 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md

---

## GR-ARCH-034: DIP (Dependency Inversion Principle) - 의존성 역전 원칙

### DON'T (안티패턴)

```java
// 안티패턴: 고수준 모듈이 저수준 모듈에 직접 의존
@Service
public class GameCharacterService {
    // ❌ 구체적인 구현체에 직접 의존
    private final RedisTemplate<String, GameCharacter> redisTemplate;
    private final GameCharacterJpaRepository jpaRepository;

    public GameCharacter findByIgn(String ign) {
        // Redis → JPA 구체적 구현에 강하게 결합
        return redisTemplate.opsForValue().get("char:" + ign)
            .orElseGet(() -> jpaRepository.findByIgn(ign));
    }
}
```

**위험성:**
- 구현체 변경 시 상위 모듈도 수정
- 단위 테스트 불가 (Redis, JPA 의존)
- 모듈 간 결합도 증가

### DO (베스트 프랙티스)

```java
// Good: 추상화(인터페이스)에 의존
// 포트 (Port) - 도메인 계층에서 정의
public interface CharacterCachePort {
    Optional<GameCharacter> findByIgn(String ign);
    void save(String ign, GameCharacter character);
}

public interface CharacterRepositoryPort {
    Optional<GameCharacter> findByIgn(String ign);
    void save(GameCharacter character);
}

// 어댑터 (Adapter) - 인프라 계층에서 구현
@Component
public class RedisCharacterCacheAdapter implements CharacterCachePort {
    private final RedisTemplate<String, GameCharacter> redisTemplate;

    @Override
    public Optional<GameCharacter> findByIgn(String ign) {
        return Optional.ofNullable(redisTemplate.opsForValue().get("char:" + ign));
    }

    @Override
    public void save(String ign, GameCharacter character) {
        redisTemplate.opsForValue().set("char:" + ign, character, Duration.ofMinutes(5));
    }
}

@Repository
public class JpaCharacterRepositoryAdapter implements CharacterRepositoryPort {
    private final GameCharacterJpaRepository jpaRepository;

    @Override
    public Optional<GameCharacter> findByIgn(String ign) {
        return jpaRepository.findByIgn(ign).map(GameCharacterJpaEntity::toDomain);
    }
}

// 서비스 - 포트(인터페이스)에만 의존
@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final CharacterCachePort cache;  // 인터페이스 의존
    private final CharacterRepositoryPort repository;  // 인터페이스 의존

    public GameCharacter findByIgn(String ign) {
        return cache.findByIgn(ign)
            .orElseGet(() -> repository.findByIgn(ign));
    }
}
```

**핵심 규칙:**
- 고수준 모듈은 저수준 모듈에 의존하지 않음
- 둘 다 추상화(인터페이스)에 의존
- 포트/어댑터 패턴 (Hexagonal Architecture)
- 멀티 모듈 구조: module-app → module-infra → module-core

### 출처
- CLAUDE.md Section 4: Implementation Logic & SOLID
- docs/16_Guardrails/architecture/adr-decisions.md - GR-ARCH-001: Hexagonal Architecture & DIP Compliance

---

## GR-ARCH-035: DDD Aggregate Root - ID 참조만 사용

### DON'T (안티패턴)

```java
// 안티패턴 1: JPA 연관관계 사용 (순환 의존성 위험)
@Entity
public class GameCharacter {
    @Id
    private Long id;

    @OneToMany(mappedBy = "character", cascade = CascadeType.ALL)  // ❌
    private List<CharacterLike> likes = new ArrayList<>();

    @OneToOne(cascade = CascadeType.ALL)  // ❌
    private CharacterEquipment equipment;
}

@Entity
public class CharacterLike {
    @ManyToOne  // ❌ 순환 참조
    private GameCharacter character;
}

// 안티패턴 2: 직접 참조로 Lazy Loading 문제
@Service
public class CharacterService {
    public CharacterDto getWithLikes(Long characterId) {
        GameCharacter character = repository.findById(characterId)
            .orElseThrow();
        // ❌ LazyInitializationException 가능
        List<CharacterLike> likes = character.getLikes();
        // ...
    }
}
```

**위험성:**
- 순환 의존성 → 직렬화 실패
- N+1 쿼리 문제
- 트랜잭션 경계 모호
- Aggregate 간 결합도 증가

### DO (베스트 프랙티스)

```java
// Good: ID 참조만 사용 (DDD Best Practice)
// Aggregate 1: GameCharacter
public record GameCharacter(
    Long id,
    UserIgn userIgn,
    CharacterId characterId,
    CharacterEquipment equipment,  // 직렬화된 값 (ID 참조 아님)
    Long likeCount,  // 비정규화된 카운터
    LocalDateTime basicInfoUpdatedAt,
    Long version,
    LocalDateTime updatedAt
) {
    // Factory Methods
    public static GameCharacter create(UserIgn userIgn, CharacterId characterId) {
        return new GameCharacter(
            null, userIgn, characterId,
            CharacterEquipment.empty(),
            0L, LocalDateTime.now(), 0L, LocalDateTime.now()
        );
    }

    // With-ers (불변 객체 패턴)
    public GameCharacter withIncrementedLike() {
        return new GameCharacter(
            id, userIgn, characterId, equipment,
            likeCount + 1, basicInfoUpdatedAt, version, LocalDateTime.now()
        );
    }

    public GameCharacter withEquipment(CharacterEquipment newEquipment) {
        return new GameCharacter(
            id, userIgn, characterId, newEquipment,
            likeCount, basicInfoUpdatedAt, version, LocalDateTime.now()
        );
    }
}

// Aggregate 2: CharacterLike (ID 참조만)
@Entity
@Table(name = "character_like")
public class CharacterLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String characterId;  // ✅ ID 참조만 (String)
    private String userId;
    private LocalDateTime createdAt;

    @Version
    private Long version;

    // JPA 연관관계 없음
}

// 서비스: 각 Aggregate 독립적으로 취급
@Service
@RequiredArgsConstructor
public class CharacterLikeService {
    private final CharacterLikeRepository likeRepository;
    private final GameCharacterRepository characterRepository;

    @Transactional
    public void addLike(String characterId, String userId) {
        // 1. 좋아요 저장 (별도 Aggregate)
        CharacterLike like = CharacterLike.create(characterId, userId);
        likeRepository.save(like);

        // 2. 캐릭터 좋아요 수 증가 (비동기로 별도 처리 가능)
        characterRepository.incrementLikeCount(characterId);
    }
}
```

**핵심 규칙:**
- **JPA @OneToMany/@ManyToOne 금지**: ID 참조만 사용
- **각 Aggregate는 독립적 트랜잭션**: 경계 명확
- **비정규화된 카운터**: `likeCount` 필드로 조회 성능 최적화
- **불변 Record**: Java Record로 도메인 모델 표현
- **ID 참조**: `String characterId`로 느슨한 결합

### 출처
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md - Section 3: Aggregate Root 분석

---

## GR-ARCH-036: Rich Domain Model vs Anemic Domain Model

### DON'T (안티패턴)

```java
// 안티패턴: 빈약한 도메인 모델 (Anemic Domain Model)
public class GameCharacter {
    private Long id;
    private String userIgn;
    private String characterId;
    private Long likeCount;

    // Getter/Setter만 존재, 행동 없음
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLikeCount() { return likeCount; }
    public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
}

// 행동이 Service로 분산됨
@Service
public class CharacterService {
    public void incrementLike(GameCharacter character) {
        character.setLikeCount(character.getLikeCount() + 1);  // 상태 변경 노출
    }

    public boolean isExpired(GameCharacter character, Duration ttl) {
        return Duration.between(character.getUpdatedAt(), LocalDateTime.now())
            .compareTo(ttl) > 0;  // 비즈니스 로직이 Service에
    }
}
```

**위험성:**
- 도메인 로직이 Service로 분산 → 유지보수 어려움
- 상태 캡슐화 실패
- 테스트가 어려움

### DO (베스트 프랙티스)

```java
// Good: 풍부한 도메인 모델 (Rich Domain Model)
public record GameCharacter(
    Long id,
    UserIgn userIgn,  // Value Object
    CharacterId characterId,  // Value Object
    CharacterEquipment equipment,
    Long likeCount,
    LocalDateTime basicInfoUpdatedAt,
    Long version,
    LocalDateTime updatedAt
) {
    // Factory Methods
    public static GameCharacter create(UserIgn userIgn, CharacterId characterId) {
        return new GameCharacter(
            null, userIgn, characterId,
            CharacterEquipment.empty(),
            0L, LocalDateTime.now(), 0L, LocalDateTime.now()
        );
    }

    // 비즈니스 행위 (메서드)
    public GameCharacter incrementLike() {
        return new GameCharacter(
            id, userIgn, characterId, equipment,
            likeCount + 1, basicInfoUpdatedAt, version, LocalDateTime.now()
        );
    }

    public boolean isExpired(Duration ttl) {
        return Duration.between(basicInfoUpdatedAt, LocalDateTime.now())
            .compareTo(ttl) > 0;
    }

    public boolean needsRefresh(Duration cacheTtl) {
        return Duration.between(basicInfoUpdatedAt, LocalDateTime.now())
            .compareTo(cacheTtl) > 0;
    }

    // 불변성 보장 (Record 특성)
}

// Value Object: 불변, 식별자 없음
public record UserIgn(String value) {
    public UserIgn {
        Objects.requireNonNull(value, "UserIgn cannot be null");
        if (value.length() < 3 || value.length() > 20) {
            throw new IllegalArgumentException("UserIgn must be 3-20 characters");
        }
    }
}

public record CharacterId(String value) {
    public CharacterId {
        Objects.requireNonNull(value, "CharacterId cannot be null");
    }
}
```

**핵심 규칙:**
- **데이터 + 행위 캡슐화**: 도메인 모델에 비즈니스 로직 포함
- **불변성**: Java Record로 불변 보장
- **Value Object**: 식별자 없는 값 객체
- **Factory Methods**: 정적 팩토리 메서드로 생성 로직 캡슐화
- **With-ers**: 상태 변경 시 새로운 인스턴스 반환

### 출처
- docs/16_Guardrails/architecture/adr-decisions.md - GR-ARCH-002: Rich Domain Model vs Anemic Domain Model
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md

---

## GR-ARCH-037: Hexagonal Architecture - 포트/어댑터 패턴

### DON'T (안티패턴)

```
안티패턴: 계층 간 결합

Controller → Service → Repository → JPA Entity
            ↓
         직접 RedisTemplate 호출
            ↓
         직접 RestTemplate 호출
```

```java
// 안티패턴: 도메인이 인프라에 의존
@Entity  // 인프라 관심사가 도메인에
public class GameCharacter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // 인프라 관심사
    private Long id;

    @Column(name = "user_ign")
    private String userIgn;

    @Transient  // 인프라 관심사
    private String cachedData;
}

@Service
public class GameCharacterService {
    private final RedisTemplate<String, GameCharacter> redisTemplate;  // 인프라 직접 의존
    private final RestTemplate restTemplate;  // 인프라 직접 의존
}
```

### DO (베스트 프랙티스)

```
베스트 프랙티스: Hexagonal Architecture

┌─────────────────────────────────────────────────────────────────┐
│                    HEXAGONAL ARCHITECTURE                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │           ADAPTERS (Infrastructure)                      │    │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐              │    │
│  │  │   REST   │  │   JPA    │  │  Redis   │              │    │
│  │  │ Adapter  │  │ Adapter  │  │ Adapter  │              │    │
│  │  └────┬─────┘  └────┬─────┘  └────┬─────┘              │    │
│  └───────┼────────────┼────────────┼───────────────────────┘    │
│          │            │            │                           │
│  ┌───────▼────────────▼────────────▼───────────────────────┐    │
│  │              PORTS (Interfaces)                         │    │
│  │  ┌─────────────────────────────────────────────────┐   │    │
│  │  │  CharacterRepository                             │   │    │
│  │  │  CharacterCache                                  │   │    │
│  │  │  ExternalApiPort                                 │   │    │
│  │  └─────────────────────────────────────────────────┘   │    │
│  └───────┬────────────────────────────────────────────────┘    │
│          │                                                     │
│  ┌───────▼────────────────────────────────────────────────┐    │
│  │              DOMAIN (Core)                             │    │
│  │  ┌─────────────────┐  ┌──────────────────────────┐    │    │
│  │  │ GameCharacter   │  │ CharacterEquipment       │    │    │
│  │  │ (Rich Model)    │  │ (Rich Model)             │    │    │
│  │  └─────────────────┘  └──────────────────────────┘    │    │
│  └──────────────────────────────────────────────────────┘    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

```java
// 1. PORT (도메인 계층에서 정의)
package maple.expectation.domain.port;

public interface CharacterRepository {
    Optional<GameCharacter> findByIgn(String ign);
    void save(GameCharacter character);
    boolean existsById(Long id);
}

// 2. DOMAIN (순수 자바, 프레임워크 독립)
package maple.expectation.domain.model;

public record GameCharacter(
    Long id,
    UserIgn userIgn,
    CharacterId characterId,
    CharacterEquipment equipment,
    Long likeCount,
    LocalDateTime basicInfoUpdatedAt,
    Long version,
    LocalDateTime updatedAt
) {
    // 순수 도메인 로직
    public GameCharacter withIncrementedLike() {
        return new GameCharacter(id, userIgn, characterId, equipment,
            likeCount + 1, basicInfoUpdatedAt, version, LocalDateTime.now());
    }
}

// 3. ADAPTER (인프라 계층에서 구현)
package maple.expectation.infrastructure.persistence;

@Repository
public class JpaCharacterRepositoryAdapter implements CharacterRepository {
    private final GameCharacterJpaRepository jpaRepository;

    @Override
    public Optional<GameCharacter> findByIgn(String ign) {
        return jpaRepository.findByIgn(ign)
            .map(GameCharacterJpaEntity::toDomain);
    }

    @Override
    public void save(GameCharacter character) {
        jpaRepository.save(GameCharacterJpaEntity.fromDomain(character));
    }
}

// 4. APPLICATION (포트를 통해 도메인 사용)
@Service
@RequiredArgsConstructor
public class GameCharacterService {
    private final CharacterRepository repository;  // 포트(인터페이스) 의존

    public GameCharacter findByIgn(String ign) {
        return repository.findByIgn(ign)
            .orElseThrow(() -> new CharacterNotFoundException(ign));
    }
}
```

**핵심 규칙:**
- **도메인은 순수 자바**: Spring/JPA 의존 없음
- **포트는 도메인에서 정의**: 인터페이스는 domain/port 패키지
- **어댑터는 인프라에서 구현**: 구체적 구현은 infrastructure/persistence
- **의존성 방향**: Adapter → Port → Domain (단방향)

### 출처
- docs/16_Guardrails/architecture/adr-decisions.md - GR-ARCH-001: Hexagonal Architecture & DIP Compliance
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md

---

## GR-ARCH-038: 멀티 모듈 의존성 방향 (DIP 준수)

### DON'T (안티패턴)

```
안티패턴: 순환 의존 또는 역방향 의존

module-core → module-infra  (도메인이 인프라에 의존 ❌)
module-infra → module-app  (인프라가 앱에 의존 ❌)
```

**위험성:**
- 도메인이 인프라 변경에 영향
- 테스트 불가 (인프라 없이 도메인 테스트 불가)
- 재사용 불가

### DO (베스트 프랙티스)

```
의존성 방향 (화살표가 의존하는 방향):

module-app ──────→  module-infra  ──────→  module-core
 (Controllers)        (Adapters)         (Ports + Domain)
                                              ↓
                                        module-common
                                        (Shared Kernel)

✅ app → infra: 애플리케이션이 인프라 어댑터 사용
✅ infra → core: 어댑터가 도메인 포트 구현
✅ all → common: 모두가 공유 커널 사용
❌ core → infra: 금지 (도메인이 인프라 의존)
❌ infra → app: 금지 (인프라가 앱 의존)
```

**검증 명령어:**
```bash
# module-core에 Spring 의존성 없음 확인
grep -r "@Component\|@Service\|@Repository" module-core/src/main/kotlin/
# Expected: No results

# 의존성 방향 확인
./gradlew module-app:dependencies --configuration runtimeClasspath | grep module-infra
# Expected: module-app → module-infra

# ArchUnit 테스트
./gradlew test --tests "maple.expectation.architecture.ArchitectureTest"
```

### 출처
- docs/16_Guardrails/architecture/adr-decisions.md - GR-ARCH-001: Hexagonal Architecture & DIP Compliance
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md

---

## Verification Commands

### SOLID 준수 검증

```bash
# SRP: 메서드 길이 확인 (20라인 초과 시 위반)
find src/main/kotlin -name "*.java" -exec wc -l {} \; | awk '$1 > 20 { print $0 }'

# OCP: switch/if-else 분기문 확인
grep -r "switch.*Type" src/main/kotlin/
grep -r "if.*type.*==" src/main/kotlin/

# LSP: null 반환 패턴 확인
grep -r "return null;" src/main/kotlin/ | grep -v "// "

# ISP: 구현하지 않는 메서드 확인
grep -r "@Override" src/main/kotlin/ | grep "throw new UnsupportedOperationException"

# DIP: 구체적 구현체 의존 확인
grep -r "new RedisTemplate\|new RestTemplate\|new JdbcTemplate" src/main/kotlin/
```

### DDD 준수 검증

```bash
# JPA 연관관계 사용 확인 (@OneToMany, @ManyToOne)
grep -r "@OneToMany\|@ManyToOne" src/main/kotlin/

# 도메인 계층에 @Entity 존재 확인
find module-core/src/main/kotlin -name "*.java" -exec grep -l "@Entity" {} \;

# ID 참조 패턴 확인 (String 필드명 *Id)
grep -r "private String.*Id;" src/main/kotlin/

# Record 사용 확인 (불변 도메인 모델)
find src/main/kotlin -name "*.java" -exec grep -l "^public record" {} \;
```

---

## Evidence Links

- CLAUDE.md Section 4: Implementation Logic & SOLID
- docs/16_Guardrails/architecture/adr-decisions.md - ADR 아키텍처 결정
- docs/05_Reports/Architecture/2026-02-22-ddd-verification-report.md - DDD 검증 리포트
- docs/00_Start_Here/architecture.md - 시스템 아키텍처 다이어그램
