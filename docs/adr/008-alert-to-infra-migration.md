# ADR-008: Discord Alert Service를 module-infra로 이관

## 상태
Proposed (2026-03-01)

## 컨텍스트
현재 `module-app/service/v2/alert/` 패키지에 Discord 알림 관련 구현체들이 위치해 있다.

### 현재 구조
```
module-app/
└── service/v2/alert/
    ├── DiscordAlertService.java      # Discord 웹훅 전송
    ├── DiscordMessageFactory.java    # 메시지 포맷팅
    └── dto/DiscordMessage.java       # DTO
```

### 문제점
1. ** infra concern 침범**: Discord 웹훅 호출은 비즈니스 로직이 아닌 인프라 구현체
2. **의존성 방향**: 이미 `infra/monitoring/ai/`, `infra/monitoring/context/`에 의존 중
3. **테스트 용이성**: 외부 API 호출이 서비스 레이어에 섞여 있어 모킹이 복잡

### 결합도 분석
| 의존 대상 | 위치 | 결합도 |
|-----------|------|--------|
| AiSreService | infra/monitoring/ai | 낮음 (Optional) |
| SystemContextProvider | infra/monitoring/context | 낮음 (Optional) |
| WebClient | Spring Framework | 낮음 (DI) |
| AlertPortAdapter | adapter/in | 낮음 (단방향) |

**결론**: 강한 결합 없음. Port 추출 없이 바로 이관 가능.

## 결정
Discord Alert 관련 클래스를 `module-infra/notification/discord/`로 이관한다.

### 이관 대상
| 파일 | 목표 위치 |
|------|-----------|
| DiscordAlertService.java | infra/notification/discord/DiscordAlertService.java |
| DiscordMessageFactory.java | infra/notification/discord/DiscordMessageFactory.java |
| dto/DiscordMessage.java | infra/notification/discord/dto/DiscordMessage.java |

### 목표 구조
```
module-infra/
└── infrastructure/notification/discord/
    ├── DiscordAlertService.java
    ├── DiscordMessageFactory.java
    └── dto/DiscordMessage.java
```

### Java → Kotlin 변환
일관성을 위해 Kotlin으로 변환하여 이관한다.

## 결과
### 긍정적 효과
1. **관심사 분리**: 알림 구현체가 infra에 위치하여 책임 명확
2. **의존성 방향**: app → infra 단방향 유지
3. **테스트 용이성**: infra 모듈에서 독립적으로 테스트 가능
4. **교체 용이성**: Discord → Slack 등 알림 채널 교체 시 infra만 수정

### 위험 요소
1. **Import 변경**: AlertPortAdapter 등 사용처의 import 수정 필요
2. **호환성**: 기존 테스트 코드의 import 수정 필요

## 이행 계획
1. [x] 결합도 분석
2. [ ] Kotlin 변환 및 infra/notification/discord/로 이관
3. [ ] module-app의 import 수정
4. [ ] 테스트 import 수정
5. [ ] 빌드/테스트 검증

## 관련 문서
- ADR-005: monitoring-infra-migration (완료)
- CLAUDE.md Section 4: SOLID 원칙
- CLAUDE.md Section 6: Design Patterns
