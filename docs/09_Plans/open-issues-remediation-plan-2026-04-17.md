# Open Issues Remediation Plan (2026-04-17)

## 목표
- 오픈 이슈 #651 ~ #695를 우선순위(P0→P2) 기준으로 단계적으로 해결한다.
- 보안/신뢰성 리스크(P0/P1)를 먼저 차단하고, 이후 아키텍처/품질 개선(P2)을 병렬 처리한다.

## 우선순위별 실행 전략

### Phase 1 — Critical Stabilization (P0/P1)
1. **#667 [P0][Auth] Login 시 Nexon API 계정 검증 강제**
   - 로그인 플로우에서 API Key 유효성 + 소유 계정 검증을 hard-fail로 보장.
   - 회귀 테스트: 유효/무효 API Key, 동일 계정 다중 키 시나리오.
2. **#693 [P1][Infra] Rate Limiter permit leak 차단**
   - acquire/release 짝 불일치, 예외 경로 release 누락 여부 점검.
   - 실패/타임아웃 경로 포함 단위 테스트 추가.
3. **#672 [P1][Infra] PostgresSingleFlightStrategy xact lock 전환 검증**
   - `pg_try_advisory_xact_lock` 사용 고정 및 세션락 회귀 방지 테스트 추가.

### Phase 2 — Security/Observability Hardening (P2, high impact)
4. **#652 [P2][Security] 인증 엔드포인트 Rate Limiting 적용**
5. **#651 [P2][Reliability] Leader Election 구조화 메트릭 추가**
6. **#653 [P2][Concurrency] PostgresNotifySubscriber @Volatile → AtomicReference 정리**

### Phase 3 — Architecture & Quality (P2)
7. **#694 [P2][Architecture] ExecutorPort 확장으로 DIP 준수**
8. **#655 [P2][Architecture] ADR-022 Redis 제거 잔여물 정리**
9. **#660 [P2][Architecture] API v1 Deprecation 계획/마이그레이션 가이드 확정**
10. **#659 [P2][Performance] JPA Batch Fetch 검증 리포트 + N+1 회귀 테스트**
11. **#658 [P2][Code-Quality] BulkLoaderService 순환복잡도 리팩터링**
12. **#657 [P2][Null-Safety] Builder `!!` 제거 (`require`/명시적 검증)**
13. **#656 [P2][Test] `Thread.sleep` → Awaitility 전환**
14. **#654 [P2][Configuration] 매직 넘버 설정 외부화**
15. **#695 [P2][Test] NexonFanOutBatchLoader 단위 테스트 보강**

## 이번 커밋에서 처리한 범위
- #695를 먼저 착수해 핵심 단위 테스트(성공/429/non-429/헬퍼 함수)를 추가.
- 나머지 이슈는 상기 Phase 순서대로 후속 브랜치에서 분할 처리 권장.

## 브랜치/PR 운영 원칙
- 각 Phase 또는 1~2개 이슈 단위로 작은 PR 생성.
- 머지 전 체크: `compileKotlin compileJava --continue`, 관련 모듈 단위 테스트, 영향도 문서 업데이트.
