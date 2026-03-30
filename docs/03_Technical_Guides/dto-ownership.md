# DTO 소유권 및 의존성 규칙

Data Transfer Object (DTO)의 위치, 네이밍, 의존성 방향을 정의합니다.

---

## 개요

DTO는 Clean Architecture에서 **계층 간 데이터 전송**을 담당합니다. 올바른 DTO 배치는 의존성 방향을 올바르게 유지하는 핵심입니다.

---

## Architecture Decision (ADR-005 기반)

### 의존성 방향

```
module-web ──────> module-app ──────> module-core ──────> module-common
     │                  │
     └──> module-infra <──┘
              │
              └──────> module-core ──────> module-common
```

**규칙**: 의존성은 항상 **바깥쪽에서 안쪽으로** 향해야 합니다.

---

## DTO 위치 규칙

### 1. API DTOs (Shared Contracts)

**위치**: `module-common/src/main/kotlin/maple/expectation/common/dto/`

**용도**:
- Request/Response DTOs
- API contract 정의
- Web, App, Infra 모든 모듈에서 사용

**예시**:
```kotlin
// module-common/src/main/kotlin/maple/expectation/common/dto/LoginRequest.kt
package maple.expectation.common.dto

data class LoginRequest(
    val apiKey: String,
    val userIgn: String,
)
```

**하위 패키지**:
```
common/dto/
├── v4/           # API 버전 v4
├── v5/           # API 버전 v5
├── page/         # 페이지네이션
├── admin/        # 관리자용
├── dlq/          # DLQ 관련
└── donation/     # 후원 관련
```

### 2. Domain DTOs (Core-specific)

**위치**: `module-core/src/main/kotlin/maple/expectation/core/dto/`

**용도**:
- Domain-specific data structures
- Calculator inputs (e.g., CubeCalculationInput)
- Port Command/Result DTOs

**예시**:
```kotlin
// module-core/src/main/kotlin/maple/expectation/core/port/inbound/AuthCommand.kt
package maple.expectation.core.port.inbound

data class AuthCommand(
    val apiKey: String,
    val userIgn: String,
)
```

### 3. Application DTOs (App-specific)

**위치**: `module-app/src/main/kotlin/maple/expectation/application/dto/`

**용도**:
- Application-specific data structures
- Internal use only
- BaseDto, CharacterEquipmentDto, etc.

**예시**:
```kotlin
// module-app/src/main/kotlin/maple/expectation/application/dto/BaseDto.kt
package maple.expectation.application.dto

interface BaseDto {
    val timestamp: Long
}
```

---

## 의존성 규칙

### ✅ 허용되는 의존성

```
module-web ──> module-common (API DTOs)
module-app ──> module-common (API DTOs)
module-app ──> module-core (Domain DTOs)
module-infra ──> module-common (API DTOs)
```

### ❌ 금지되는 의존성

```
module-app ──> module-web (금지! 역방향 의존)
module-core ──> module-web (금지! Core 순수성 위반)
module-core ──> module-app (금지! 역방향 의존)
```

---

## 네이밍 규칙

| 접미사 | 용도 | 예시 |
|--------|------|------|
| `*Request` | API 요청 DTO | `LoginRequest`, `RefreshRequest` |
| `*Response` | API 응답 DTO | `LoginResponse`, `TokenResponse` |
| `*Input` | 계산 입력 DTO | `EquipmentCalculationInput`, `CubeCalculationInput` |
| `*Command` | Port 입력 DTO | `AuthCommand`, `DonationCommand` |
| `*Result` | Port 출력 DTO | `AuthResult`, `TokenResult` |
| `*Dto` | 일반 DTO | `CostBreakdownDto`, `CubeExpectationDto` |

### 버저닝 규칙

API 버전이 다른 경우 접미사로 버전 표기:

```kotlin
// v4
EquipmentExpectationResponseV4

// v5
EquipmentExpectationResponseV5
```

---

## 금지 사항

### 1. Service에서 직접 Request/Response 사용 지양

```kotlin
// ❌ Bad: Service가 web DTO를 직접 사용
class AuthService {
    fun login(request: LoginRequest): LoginResponse { ... }
}

// ✅ Good: Service는 core Command/Result 사용
class AuthService {
    fun login(command: AuthCommand): AuthResult { ... }
}

// Controller에서 변환
class AuthController {
    fun login(request: LoginRequest): LoginResponse {
        val command = AuthCommand(request.apiKey, request.userIgn)
        val result = authService.login(command)
        return LoginResponse.from(result)
    }
}
```

### 2. DTO 순환 의존 금지

```kotlin
// ❌ Bad: DTO가 다른 DTO를 참조하며 순환 발생
data class UserDto(val profile: ProfileDto)
data class ProfileDto(val user: UserDto)  // 순환!

// ✅ Good: ID로 참조
data class UserDto(val profileId: Long)
data class ProfileDto(val userId: Long)
```

### 3. module-app이 module-web import 금지

```kotlin
// ❌ Bad: module-app에서 web DTO import
import maple.expectation.web.dto.LoginRequest

// ✅ Good: module-common에서 DTO import
import maple.expectation.common.dto.LoginRequest
```

---

## 마이그레이션 가이드

### web.dto → common.dto 이관 절차

1. **파일 이동**:
   ```bash
   mv module-web/src/main/kotlin/.../web/dto/LoginRequest.kt \
      module-common/src/main/kotlin/.../common/dto/
   ```

2. **패키지 변경**:
   ```kotlin
   // Before
   package maple.expectation.web.dto

   // After
   package maple.expectation.common.dto
   ```

3. **Import 수정**:
   ```kotlin
   // Before
   import maple.expectation.web.dto.LoginRequest

   // After
   import maple.expectation.common.dto.LoginRequest
   ```

4. **build.gradle 확인**:
   ```groovy
   // module-app/build.gradle에서 제거
   // implementation project(':module-web')  // 제거!
   ```

---

## ArchUnit 검증

다음 ArchUnit 테스트로 규칙 준수를 검증합니다:

```java
@ArchTest
static final ArchRule app_should_not_depend_on_web =
    noClasses()
        .that().resideInAPackage("..app..")
        .should().dependOnClassesThat()
        .resideInAPackage("..web..")
        .because("Application layer must not depend on presentation layer");

@ArchTest
static final ArchRule request_response_dtos_should_be_in_common =
    classes()
        .that().haveSimpleNameEndingWith("Request")
        .or().haveSimpleNameEndingWith("Response")
        .should().resideInAPackage("..common.dto..")
        .because("Request/Response DTOs are shared API contracts");
```

---

## 관련 문서

- [ADR-005: Module Dependency Strategy](../01_ADR/ADR-005-module-dependency-strategy.md)
- [Port 인터페이스 작성 가이드](./port-guide.md)
- [Service Modules Guide](./service-modules.md)
