# Port 인터페이스 작성 가이드

Hexagonal Architecture (Ports & Adapters)의 Port 인터페이스 작성 표준을 정의합니다.

---

## 개요

Port는 Clean Architecture에서 **핵심 도메인과 외부 시스템 간의 경계**를 정의하는 인터페이스입니다. 모든 Port는 `module-core`에 정의되며, 구현체는 `module-infra`에 위치합니다.

---

## 위치 규칙

### Port 인터페이스 위치

```
module-core/src/main/kotlin/maple/expectation/core/
├── port/
│   ├── inbound/          # 유스케이스 진입점 (Controller → Application)
│   │   ├── AuthPort.kt
│   │   ├── ExpectationV4Port.kt
│   │   └── DlqPort.kt
│   └── out/              # 외부 시스템 연동 (Application → Infrastructure)
│       ├── GameCharacterPort.kt
│       ├── CachePort.kt
│       └── EventPublisher.kt
├── calculator/port/       # 도메인별 하위 패키지
│   ├── CubeCostPort.kt
│   └── CubeRatePort.kt
└── flame/port/
    └── FlameTrialsPort.kt
```

### 구현체 위치

```
module-infra/src/main/kotlin/maple/expectation/infra/adapter/
├── inbound/
│   └── AuthPortAdapter.kt
└── out/
    └── GameCharacterPortAdapter.kt
```

---

## Port 분류

### Inbound Port (Primary Port)

**역할**: 외부 요청을 도메인으로 전달하는 진입점

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthPort.kt
package maple.expectation.core.port.inbound

/**
 * 인증 포트 (ADR-005)
 *
 * <h3>역할</h3>
 * <p>인증 관련 유스케이스를 정의하는 인바운드 포트
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>AuthPortAdapter: AuthService에 위임
 * </ul>
 *
 * <h3>설계 원칙</h3>
 * <ul>
 *   <li>module-core는 web DTO를 참조하지 않음
 *   <li>Port 전용 Command/Result DTO 사용
 *   <li>Adapter에서 web DTO ↔ core DTO 변환
 * </ul>
 */
interface AuthPort {
    fun login(command: AuthCommand): AuthResult
    fun logout(sessionId: String)
    fun refresh(refreshTokenId: String): TokenResult
}
```

### Outbound Port (Secondary Port)

**역할**: 도메인에서 외부 시스템으로의 연동 인터페이스

```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/out/GameCharacterPort.kt
package maple.expectation.core.port.out

import java.util.Optional
import maple.expectation.core.domain.model.character.GameCharacter

/**
 * 게임 캐릭터 포트 (ADR-005)
 *
 * <h3>역할</h3>
 * <p>캐릭터 조회, 생성, 저장을 위한 인터페이스
 *
 * <h3>구현체</h3>
 * <ul>
 *   <li>GameCharacterPortAdapter: GameCharacterService에 위임
 * </ul>
 */
interface GameCharacterPort {
    fun isNonExistent(userIgn: String): Boolean
    fun getCharacterIfExist(userIgn: String): Optional<GameCharacter>
    fun createNewCharacter(userIgn: String): GameCharacter
    fun saveCharacter(character: GameCharacter): String
    fun getCharacterOrThrow(userIgn: String): GameCharacter
    fun enrichCharacterBasicInfo(character: GameCharacter): GameCharacter
    fun getCharacterForUpdate(userIgn: String): GameCharacter
}
```

---

## 네이밍 규칙

| 요소 | 규칙 | 예시 |
|------|------|------|
| 인터페이스명 | `*Port` 접미사 | `AuthPort`, `GameCharacterPort` |
| 메서드명 | 도메인 언어 사용 | `login`, `saveCharacter`, `enrichBasicInfo` |
| Command DTO | `*Command` 접미사 | `AuthCommand`, `DonationCommand` |
| Result DTO | `*Result` 접미사 | `AuthResult`, `TokenResult` |
| 구현체명 | `*PortAdapter` 접미사 | `AuthPortAdapter` |

---

## 설계 원칙

### 1. Core 모듈 순수성 유지

```kotlin
// ✅ Good: Core는 순수 Kotlin 인터페이스
interface AuthPort {
    fun login(command: AuthCommand): AuthResult
}

// ❌ Bad: Spring annotation 사용
interface AuthPort {
    @Transactional  // 금지!
    fun login(command: AuthCommand): AuthResult
}
```

### 2. DTO 변환은 Adapter에서 수행

```kotlin
// AuthPortAdapter.kt (module-infra)
class AuthPortAdapter(
    private val authService: AuthService
) : AuthPort {

    override fun login(command: AuthCommand): AuthResult {
        // web DTO → core DTO 변환은 여기서
        return authService.login(command)
    }
}
```

### 3. 단일 책임 원칙

각 Port는 하나의 도메인 영역만 담당:
- `AuthPort`: 인증만
- `GameCharacterPort`: 캐릭터 조회/저장만
- `EventPublisher`: 이벤트 발행만

---

## 기존 Port 목록

### Inbound Ports (16개)

| Port | 역할 | 위치 |
|------|------|------|
| AuthPort | 인증 (login/logout/refresh) | `port/inbound/` |
| ExpectationV4Port | 장비 기대값 계산 V4 | `port/inbound/` |
| CharacterViewQueryPort | 캐릭터 조회 | `port/inbound/` |
| CalculationQueuePort | 계산 큐 관리 | `port/inbound/` |
| DlqPort | DLQ 관리 | `port/inbound/` |
| AdminPort | 관리자 기능 | `port/inbound/` |
| DonationPort | 후원 기능 | `port/inbound/` |
| AlertPort | 알림 기능 | `port/inbound/` |

### Outbound Ports (30개+)

| Port | 역할 | 위치 |
|------|------|------|
| GameCharacterPort | 캐릭터 CRUD | `port/out/` |
| CacheWarmupPort | 캐시 워밍 | `port/out/` |
| EventPublisher | 이벤트 발행 | `port/out/` |
| OcidQueryPort | OCID 조회 | `port/out/` |
| TokenPort | 토큰 관리 | `port/out/` |
| OutboxProcessorPort | 아웃박스 처리 | `port/out/` |

### Calculator Ports (도메인별)

| Port | 역할 | 위치 |
|------|------|------|
| CubeCostPort | 큐브 비용 계산 | `calculator/port/` |
| CubeRatePort | 큐브 확률 조회 | `calculator/port/` |
| StarforceLookupPort | 스타포스 정보 | `calculator/port/` |
| FlameTrialsPort | 화염 시뮬레이션 | `flame/port/` |

---

## 관련 문서

- [ADR-005: Module Dependency Strategy](../adr/ADR-005-module-dependency-strategy.md)
- [DTO Ownership Rules](./dto-ownership.md)
- [Service Modules Guide](./service-modules.md)
