# ADR-011: Equipment Cache 및 Worker를 module-infra로 이관

## 상태
Proposed (2026-03-01)

## 컨텍스트

### 현재 구조
```
module-app/service/v2/cache/
├── AbstractTieredCacheService.java    # L1/L2 템플릿
├── EquipmentCacheService.java         # 장비 캐시 (EquipmentCache 구현)
├── EquipmentDataResolver.java         # DB → API 데이터 리졸버
├── EquipmentFingerprintGenerator.java # 캐시 키 생성
└── TotalExpectationCacheService.java  # 통합 캐시

module-app/service/v2/worker/
├── EquipmentDbWorker.java             # 장비 DB 비동기 저장
└── GameCharacterWorker.java           # 캐릭터 작업 (서비스 의존)
```

### 문제점
1. **인프라 침범**: CacheManager, RedisSerializer 등 인프라가 service 레이어에 위치
2. **관심사 혼재**: DB 비동기 저장(EquipmentDbWorker)이 service 패키지에 있음
3. **의존성 방향**: service → infra 의존은 허용되지만, infra 구현체가 service에 있는 것은 부적절

### 의존성 분석
| 클래스 | 외부 의존 | 내부 의존 | 이관 대상 |
|--------|----------|----------|----------|
| AbstractTieredCacheService | CacheManager, LogicExecutor | - | ✅ |
| EquipmentCacheService | AbstractTieredCacheService, EquipmentDbWorker | EquipmentCache (Core Port) | ✅ |
| EquipmentDataResolver | EquipmentDataProvider, EquipmentDbWorker | - | ✅ |
| EquipmentFingerprintGenerator | CheckedLogicExecutor | - | ✅ |
| TotalExpectationCacheService | CacheManager, RedisSerializer | - | ✅ |
| EquipmentDbWorker | CharacterEquipmentRepository, PersistenceTrackerStrategy | - | ✅ |
| GameCharacterWorker | GameCharacterService (service!) | - | ❌ 유지 |

## 결정

### 이관 대상
```
module-infra/infrastructure/cache/
├── tiered/
│   └── AbstractTieredCacheService.kt
├── equipment/
│   ├── EquipmentCacheService.kt
│   ├── EquipmentDataResolver.kt
│   └── EquipmentFingerprintGenerator.kt
└── expectation/
    └── TotalExpectationCacheService.kt

module-infra/infrastructure/persistence/
└── EquipmentDbWorker.kt
```

### 이관 순서 (의존성 기반)
1. `AbstractTieredCacheService` (의존 없음)
2. `EquipmentFingerprintGenerator` (의존 없음)
3. `EquipmentDbWorker` (Core Port만 의존)
4. `TotalExpectationCacheService` (의존 없음)
5. `EquipmentCacheService` (Abstract + Worker 의존)
6. `EquipmentDataResolver` (Worker 의존)

### Java → Kotlin 변환
- 모든 파일을 Kotlin으로 변환하여 module-infra 일관성 유지

## 결과

### 긍정적 효과
1. **관심사 분리**: 인프라 코드가 infra 모듈에 위치
2. **의존성 방향**: app → infra → core로 명확한 방향
3. **테스트 용이성**: 인프라 모듈 독립 테스트 가능
4. **재사용성**: AbstractTieredCacheService를 다른 모듈에서도 사용 가능

### 위험 요소
1. **Import 변경**: service 레이어의 참조 변경 필요
2. **순환 의존 가능성**: EquipmentCacheService가 service를 참조하는 경우

## 이행 계획
1. [x] ADR 작성
2. [ ] AbstractTieredCacheService 이관 (Java → Kotlin)
3. [ ] EquipmentFingerprintGenerator 이관
4. [ ] EquipmentDbWorker 이관
5. [ ] TotalExpectationCacheService 이관
6. [ ] EquipmentCacheService 이관
7. [ ] EquipmentDataResolver 이관
8. [ ] 기존 서비스 import 수정
9. [ ] 테스트 검증

## 관련 문서
- ADR-009: cache-to-infra-migration
- ADR-003: hexagonal-architecture-adoption
- CLAUDE.md Section 4: SOLID 원칙 (DIP)
