 # ADR-327: Cache Service를 module-infra로 이관 (Port 추출 포함)

## 상태
Proposed (2026-03-01)

## 컨텍스트
현재 `module-app/service/v2/cache/`에 10개의 캐시 관련 클래스가 위치해 있다.

### 현재 구조
```
module-app/service/v2/cache/
├── AbstractTieredCacheService.java    # L1/L2 템플릿
├── EquipmentCacheService.java         # 장비 캐시
├── EquipmentDataResolver.java         # 장비 데이터 리졸버
├── EquipmentFingerprintGenerator.java # 지문 생성
├── LikeBufferStorage.java             # Caffeine 버퍼
├── LikeBufferStrategy.java            # 전략 인터페이스
├── LikeRelationBuffer.java            # L1+L2 관계 버퍼
├── LikeRelationBufferStrategy.java    # 전략 인터페이스
├── RedisLikeRelationBufferAdapter.java # Redis 어댑터
└── TotalExpectationCacheService.java  # 통합 캐시
```

### 문제점
1. **인프라 침범**: Caffeine, Redis 구현체가 서비스 레이어에 위치
2. **전략 패턴 오용**: 인터페이스와 구현체가 같은 패키지
3. **강한 결합**: 도메인 서비스가 구현체에 직접 의존
4. **infra/cache 중복**: 이미 `module-infra/cache/`에 TieredCache 존재

### 의존성 분석
| 클래스 | 외부 의존 | 내부 의존 | 사용처 |
|--------|----------|----------|--------|
| LikeBufferStorage | Caffeine, Micrometer | - | LikeSyncService |
| LikeRelationBuffer | Caffeine, Redisson, LogicExecutor | - | LikeRelationSyncService |
| EquipmentCacheService | CacheManager | AbstractTieredCacheService | V4Service |
| AbstractTieredCacheService | CacheManager, LogicExecutor | - | 상속됨 |

## 결정
**Port 추출 후 이관** 전략 적용.

### Phase 1: Port 인터페이스 정의 (module-core)
```java
// core/port/out/LikeBufferPort.java
public interface LikeBufferPort {
    Long increment(String userIgn, long delta);
    Long get(String userIgn);
    Map<String, Long> fetchAndClear(int limit);
}

// core/port/out/LikeRelationBufferPort.java
public interface LikeRelationBufferPort {
    boolean addIfAbsent(String accountId, String targetOcid);
    Set<String> flushPendingToL2();
    Set<String> getAllFromL2();
}
```

### Phase 2: 구현체 이관 (module-infra)
```
module-infra/infrastructure/cache/
├── tiered/
│   └── AbstractTieredCacheService.kt
├── like/
│   ├── LikeBufferStorage.kt
│   ├── LikeRelationBuffer.kt
│   └── RedisLikeRelationBufferAdapter.kt
└── equipment/
    ├── EquipmentCacheService.kt
    ├── EquipmentDataResolver.kt
    └── EquipmentFingerprintGenerator.kt
```

### Phase 3: Adapter 구현 (module-app)
```java
// adapter/out/LikeBufferPortAdapter.java
@Component
@RequiredArgsConstructor
public class LikeBufferPortAdapter implements LikeBufferPort {
    private final LikeBufferStorage storage;
    // delegate to storage
}
```

## 결과
### 긍정적 효과
1. **DIP 준수**: 도메인이 인터페이스에만 의존
2. **교체 용이성**: In-memory → Redis 교체 시 adapter만 변경
3. **테스트 용이성**: Mock Port로 도메인 테스트 가능
4. **관심사 분리**: 캐시 구현체가 infra에 위치

### 위험 요소
1. **다단계 변경**: Port 추출 → 이관 → Adapter 연결
2. **기존 의존성**: Strategy 패턴 제거로 인한 영향
3. **@ConditionalOnProperty**: 빈 선택 로직 재구성 필요

## 이행 계획
1. [ ] ADR 작성
2. [ ] Port 인터페이스 정의 (module-core)
3. [ ] 구현체 Kotlin 변환 및 이관 (module-infra)
4. [ ] Adapter 구현 (module-app)
5. [ ] 기존 서비스 의존성 수정
6. [ ] 테스트 검증

## 관련 문서
- ADR-008: alert-to-infra-migration
- CLAUDE.md Section 4: SOLID 원칙 (DIP)
- CLAUDE.md Section 6: Design Patterns (Adapter)
