# 모듈 경계 규칙 (Hexagonal Architecture)

## 모듈 의존성 그래프 (엄격 준수)

```
module-common (Spring 의존성 제로, Gradle task로 검증)
    ↑
module-core (순수 도메인, Spring 없음, Jackson + Kotlin만)
    ↑
module-infra (인프라 어댑터 구현체, JPA, 외부 API)
    ↑
module-web (컨트롤러, DTO, Security)
    ↑
module-app (Spring Boot 애플리케이션, wiring)
```

## module-common 제약

- Spring framework 의존성 절대 금지 (Gradle `verifyNoSpringDependency` task로 빌드 시 검증)
- 에러 코드, 기본 예외, 유틸리티, cross-cutting type만 위치

## module-core 제약

- 순수 도메인 모델, port interface, domain service, calculator만 위치
- Spring annotation, JPA, infra 구현체 금지
- 의존성: `module-common`, Kotlin stdlib, Jackson만

## Port/Adapter 컨벤션

- **Inbound port** (use case): `module-core/.../core/port/inbound/` — `XxxPort` 인터페이스
- **Outbound port** (SPI): `module-core/.../core/port/out/` — `XxxPort` 인터페이스
- **Adapter**: `module-infra/.../adapter/outgoing/XxxPortAdapter` — core port 구현체
- **Controller**: port interface에만 의존. `module-infra` 직접 import 금지
- 새 service 추가 시 port interface를 먼저 정의, adapter 후에 구현

## 금지 패턴

- `module-web` → `module-infra` 직접 의존 금지
- `@Qualifier` 기반 직접 주입 대신 전용 Port interface 사용 (DIP 준수)
- Coordinator 300줄 초과 시 반드시 분해

## 모듈 이관 후 체크리스트

1. 모든 import 업데이트
2. 중복 bean 정의 검색 및 제거
3. `./gradlew compileKotlin compileJava --continue` 실행
4. 전체 test suite 실행
5. `module-app`에 stale reference 없는지 확인
